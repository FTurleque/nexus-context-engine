package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.IndexedSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorSymbolRangeUpgradeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshDatabaseAppliesRangeCompatibilityMigration() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("fresh-home"));
        SqliteDatabase database = new SqliteDatabase(paths);

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT script_name, script_sha256 FROM schema_migrations WHERE version = 4")) {
            assertTrue(resultSet.next());
            assertEquals(
                    "db/migration/V004__invalidate_invalid_symbol_ranges.sql",
                    resultSet.getString("script_name"));
            assertEquals(64, resultSet.getString("script_sha256").length());
        }
    }

    @Test
    void legacyInvalidRangesInvalidateOnlyAffectedProjectAndRemainIdempotent() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("legacy-home"));
        Path databaseFile = paths.databaseFile();
        bootstrapMainCompatibleDatabase(databaseFile);

        UUID invalidProject = UUID.randomUUID();
        UUID validProject = UUID.randomUUID();
        seedProject(databaseFile, invalidProject, 101, -1, -1, 5);
        seedProject(databaseFile, validProject, 202, 3, 7, 11);

        SqliteDatabase upgraded = new SqliteDatabase(paths);
        SqliteIndexRepository repository = new SqliteIndexRepository(upgraded);

        assertProjectState(upgraded, invalidProject, "NOT_INDEXED", null, 6, 0, 0, 0);
        assertProjectState(upgraded, validProject, "READY", "2026-01-01T00:00:00Z", 11, 1, 1, 1);

        assertTrue(repository.findSymbols(invalidProject).isEmpty(),
                "la lecture domaine ne doit jamais reconstruire un ancien CodeSymbol invalide");
        List<IndexedSymbol> validSymbols = repository.findSymbols(validProject);
        assertEquals(1, validSymbols.size());
        assertEquals(3, validSymbols.getFirst().symbol().startLine());
        assertEquals(7, validSymbols.getFirst().symbol().endLine());

        SqliteDatabase reopened = new SqliteDatabase(paths);
        assertProjectState(reopened, invalidProject, "NOT_INDEXED", null, 6, 0, 0, 0);
        assertProjectState(reopened, validProject, "READY", "2026-01-01T00:00:00Z", 11, 1, 1, 1);
    }

    private static void bootstrapMainCompatibleDatabase(Path databaseFile) throws Exception {
        Files.createDirectories(databaseFile.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            executeMigrationResource(statement, "db/migration/V001__initial_schema.sql");
            executeMigrationResource(statement, "db/migration/V002__index_generation.sql");
            executeMigrationResource(statement, "db/migration/V003__provider_and_graph_indexes.sql");
            statement.executeUpdate("""
                    CREATE TABLE schema_migrations (
                        version INTEGER PRIMARY KEY,
                        script_name TEXT NOT NULL,
                        applied_at TEXT NOT NULL,
                        script_sha256 TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO schema_migrations(version, script_name, applied_at, script_sha256)
                    VALUES
                      (1, 'db/migration/V001__initial_schema.sql', '2026-01-01T00:00:00Z', NULL),
                      (2, 'db/migration/V002__index_generation.sql', '2026-01-01T00:00:00Z', NULL),
                      (3, 'db/migration/V003__provider_and_graph_indexes.sql', '2026-01-01T00:00:00Z', NULL)
                    """);
        }
    }

    private static void executeMigrationResource(Statement statement, String resource) throws Exception {
        ClassLoader classLoader = SchemaMigratorSymbolRangeUpgradeTest.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            assertFalse(input == null, "fixture migration introuvable : " + resource);
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String withoutComments = script.lines()
                    .map(line -> line.stripLeading().startsWith("--") ? "" : line)
                    .collect(Collectors.joining("\n"));
            for (String sql : withoutComments.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql.trim());
                }
            }
        }
    }

    private static void seedProject(
            Path databaseFile,
            UUID projectId,
            long fileId,
            int startLine,
            int endLine,
            long generation) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            String id = projectId.toString();
            statement.executeUpdate("""
                    INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                    VALUES ('%s', 'project-%s', '/tmp/%s', 'LOCAL', '2026-01-01T00:00:00Z', 'READY')
                    """.formatted(id, fileId, id));
            statement.executeUpdate("""
                    INSERT INTO project_index_generations(project_id, generation)
                    VALUES ('%s', %d)
                    """.formatted(id, generation));
            statement.executeUpdate("""
                    INSERT INTO indexed_files(
                        id, project_id, relative_path, language, size_bytes,
                        content_hash, modified_at, estimated_tokens, category)
                    VALUES (%d, '%s', 'src/Foo%d.java', 'java', 42,
                            'hash-%d', '2026-01-01T00:00:00Z', 10, 'SOURCE')
                    """.formatted(fileId, id, fileId, fileId));
            statement.executeUpdate("""
                    INSERT INTO symbols(
                        file_id, kind, name, qualified_name, signature,
                        start_line, end_line, source_provider)
                    VALUES (%d, 'CLASS', 'Foo%d', 'example.Foo%d', 'class Foo%d', %d, %d, 'javaparser')
                    """.formatted(fileId, fileId, fileId, fileId, startLine, endLine));
            statement.executeUpdate("""
                    INSERT INTO symbol_relations(
                        project_id, file_id, kind, source_ref, target_ref, confidence, source_provider)
                    VALUES ('%s', %d, 'IMPORTS', 'src/Foo%d.java', 'java.lang.String', 1.0, 'javaparser')
                    """.formatted(id, fileId, fileId));
        }
    }

    private static void assertProjectState(
            SqliteDatabase database,
            UUID projectId,
            String expectedStatus,
            String expectedLastIndexedAt,
            long expectedGeneration,
            long expectedFiles,
            long expectedSymbols,
            long expectedRelations) throws Exception {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet project = statement.executeQuery("""
                    SELECT index_status, last_indexed_at
                    FROM projects
                    WHERE id = '%s'
                    """.formatted(projectId))) {
                assertTrue(project.next());
                assertEquals(expectedStatus, project.getString("index_status"));
                if (expectedLastIndexedAt == null) {
                    assertNull(project.getString("last_indexed_at"));
                } else {
                    assertEquals(expectedLastIndexedAt, project.getString("last_indexed_at"));
                }
            }
            assertEquals(expectedGeneration, scalar(statement,
                    "SELECT generation FROM project_index_generations WHERE project_id = '" + projectId + "'"));
            assertEquals(expectedFiles, scalar(statement,
                    "SELECT COUNT(*) FROM indexed_files WHERE project_id = '" + projectId + "'"));
            assertEquals(expectedSymbols, scalar(statement, """
                    SELECT COUNT(*) FROM symbols s
                    JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = '%s'
                    """.formatted(projectId)));
            assertEquals(expectedRelations, scalar(statement,
                    "SELECT COUNT(*) FROM symbol_relations WHERE project_id = '" + projectId + "'"));
        }
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
