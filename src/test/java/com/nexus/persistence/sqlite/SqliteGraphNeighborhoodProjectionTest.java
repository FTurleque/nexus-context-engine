package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteGraphNeighborhoodProjectionTest {

    private static final int UNRELATED_STRUCTURAL_TYPES = 20_000;

    @TempDir
    Path tempDir;

    @Test
    void projectsOnlyRequestedGraphNeighborhoodAndHonorsEdgeBudget() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("home")));
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            insertProject(connection, projectId);
            long app = insertFile(connection, projectId, "src/App.java");
            long dependency = insertFile(connection, projectId, "src/Dependency.java");
            long caller = insertFile(connection, projectId, "src/Caller.java");
            long unrelated = insertFile(connection, projectId, "src/Unrelated.java");

            insertType(connection, app, "App", "demo.App");
            insertType(connection, dependency, "Dependency", "demo.Dependency");
            insertType(connection, caller, "Caller", "demo.Caller");
            insertType(connection, unrelated, "Unrelated", "demo.Unrelated");

            insertImport(connection, projectId, app, "src/App.java", "demo.Dependency.Inner");
            insertImport(connection, projectId, caller, "src/Caller.java", "demo.App");
            insertImport(connection, projectId, unrelated, "src/Unrelated.java", "demo.Dependency");
        }

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        Map<String, Set<String>> neighbors =
                repository.findGraphNeighbors(projectId, Set.of("src/App.java"), 10);

        assertEquals(
                Set.of("src/Dependency.java", "src/Caller.java"),
                neighbors.get("src/App.java"));

        Map<String, Set<String>> bounded =
                repository.findGraphNeighbors(projectId, Set.of("src/App.java"), 1);
        long materializedEdges = bounded.values().stream().mapToLong(Set::size).sum();
        assertTrue(materializedEdges <= 1, "graph projection must honor the global edge budget");
    }

    @Test
    void remainsBoundedAndDeterministicWithHighStructuralTypeCardinality() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("high-cardinality-home")));
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            insertProject(connection, projectId);
            long seed = insertFile(connection, projectId, "src/Seed.java");
            long target = insertFile(connection, projectId, "src/Target.java");
            long importer = insertFile(connection, projectId, "src/Importer.java");
            long unrelated = insertFile(connection, projectId, "src/StructuralNoise.java");

            insertType(connection, seed, "Seed", "demo.Seed");
            insertType(connection, target, "Target", "demo.Target");
            insertType(connection, target, "Inner", "demo.Target.Inner");
            insertType(connection, importer, "Importer", "demo.Importer");
            insertStructuralTypes(connection, unrelated, UNRELATED_STRUCTURAL_TYPES);

            insertImport(connection, projectId, seed, "src/Seed.java", "demo.Target.Inner.Deep");
            insertImport(connection, projectId, importer, "src/Importer.java", "demo.Seed");
            insertImport(connection, projectId, unrelated, "src/StructuralNoise.java", "noise.Type19999");
        }

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        Map<String, Set<String>> expected = Map.of(
                "src/Seed.java", Set.of("src/Target.java", "src/Importer.java"));

        for (int attempt = 0; attempt < 5; attempt++) {
            Map<String, Set<String>> projection =
                    repository.findGraphNeighbors(projectId, Set.of("src/Seed.java"), 2);
            assertEquals(expected, projection, "graph projection must be deterministic");
            long materializedEdges = projection.values().stream().mapToLong(Set::size).sum();
            assertTrue(materializedEdges <= 2, "graph projection must stay within the global edge budget");
        }
    }

    private static void insertProject(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, 'graph-test', ?, 'LOCAL', NULL, 'READY')
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, "/graph-test/" + projectId);
            statement.executeUpdate();
        }
    }

    private static long insertFile(Connection connection, UUID projectId, String relativePath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO indexed_files(
                    project_id, relative_path, language, size_bytes,
                    content_hash, modified_at, estimated_tokens, category)
                VALUES (?, ?, 'java', 1, ?, '2026-08-08T00:00:00Z', 1, 'SOURCE')
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, relativePath);
            statement.setString(3, "hash-" + relativePath);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, relativePath);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("missing inserted file " + relativePath);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static void insertType(Connection connection, long fileId, String name, String qualifiedName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbols(
                    file_id, kind, name, qualified_name, signature,
                    start_line, end_line, source_provider)
                VALUES (?, 'CLASS', ?, ?, ?, 1, 2, 'embedded')
                """)) {
            statement.setLong(1, fileId);
            statement.setString(2, name);
            statement.setString(3, qualifiedName);
            statement.setString(4, "class " + name);
            statement.executeUpdate();
        }
    }

    private static void insertStructuralTypes(Connection connection, long fileId, int count) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbols(
                    file_id, kind, name, qualified_name, signature,
                    start_line, end_line, source_provider)
                VALUES (?, 'CLASS', ?, ?, ?, ?, ?, 'embedded')
                """)) {
            for (int index = 0; index < count; index++) {
                String name = "Type" + index;
                statement.setLong(1, fileId);
                statement.setString(2, name);
                statement.setString(3, "noise." + name);
                statement.setString(4, "class " + name);
                statement.setInt(5, index + 1);
                statement.setInt(6, index + 1);
                statement.addBatch();
                if ((index + 1) % 1_000 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private static void insertImport(
            Connection connection,
            UUID projectId,
            long fileId,
            String sourcePath,
            String targetRef) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbol_relations(
                    project_id, file_id, kind, source_ref, target_ref, confidence, source_provider)
                VALUES (?, ?, 'IMPORTS', ?, ?, 1.0, 'embedded')
                """)) {
            statement.setString(1, projectId.toString());
            statement.setLong(2, fileId);
            statement.setString(3, sourcePath);
            statement.setString(4, targetRef);
            statement.executeUpdate();
        }
    }
}
