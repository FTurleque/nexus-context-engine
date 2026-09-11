package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteIndexedSubstringSearchTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void embeddedSqliteQualifiesCompactFts5TrigramSupport() throws Exception {
        SqliteDatabase database = database("qualification");
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            String version;
            try (ResultSet resultSet = statement.executeQuery("SELECT sqlite_version()")) {
                assertTrue(resultSet.next());
                version = resultSet.getString(1);
            }
            assertTrue(
                    versionAtLeast(version, 3, 34, 0),
                    () -> "FTS5 trigram requires SQLite >= 3.34.0, embedded version is " + version);

            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT sqlite_compileoption_used('ENABLE_FTS5')")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1), "Embedded SQLite must be compiled with FTS5");
            }

            statement.execute("""
                    CREATE VIRTUAL TABLE temp.nexus_fts5_trigram_probe
                    USING fts5(
                        value,
                        tokenize='trigram',
                        content='',
                        detail='none',
                        columnsize=0
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO nexus_fts5_trigram_probe(rowid, value)
                    VALUES (1, 'AlphaNeedleOmega')
                    """);
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM nexus_fts5_trigram_probe
                    WHERE nexus_fts5_trigram_probe MATCH '"nee" AND "eed" AND "edl" AND "dle"'
                    """)) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }
            statement.executeUpdate("""
                    INSERT INTO nexus_fts5_trigram_probe(
                        nexus_fts5_trigram_probe, rowid, value)
                    VALUES ('delete', 1, 'AlphaNeedleOmega')
                    """);
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM nexus_fts5_trigram_probe
                    WHERE nexus_fts5_trigram_probe MATCH '"nee"'
                    """)) {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
            }
        }
    }

    @Test
    void trigramIndexesBackContainsSearchAndFlushAtGenerationBoundary() throws Exception {
        SqliteDatabase database = database("search");
        UUID projectId = UUID.randomUUID();
        long fileId;
        try (Connection connection = database.openConnection()) {
            fileId = insertProjectFile(connection, projectId);
            insertSymbol(connection, fileId, "AlphaScaleNeedleService", "demo.AlphaScaleNeedleService");
            insertRelation(
                    connection,
                    projectId,
                    fileId,
                    "demo.AlphaScaleNeedleService",
                    "demo.TargetNeedlePort");
            bumpGeneration(connection, projectId);

            assertEquals(0L, pendingCount(connection, "symbol_search_pending"));
            assertEquals(0L, pendingCount(connection, "relation_search_pending"));
            assertVirtualIndexPlan(connection, "symbol_search_fts", "ScaleNeedle");
            assertVirtualIndexPlan(connection, "relation_search_fts", "TargetNeedle");
            assertFuzzyIndexPlan(connection);
        }

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        List<IndexedSymbol> symbols = repository.searchSymbols(projectId, "ScaleNeedle", 20);
        assertFalse(symbols.isEmpty());
        assertTrue(symbols.stream().anyMatch(symbol -> symbol.symbol().name().equals("AlphaScaleNeedleService")));

        List<SymbolRelation> relations = repository.searchRelations(projectId, "TargetNeedle", 20);
        assertEquals(1, relations.size());
        assertEquals("demo.TargetNeedlePort", relations.getFirst().target());

        try (Connection connection = database.openConnection();
             PreparedStatement rename = connection.prepareStatement("""
                     UPDATE symbols
                     SET name = ?, qualified_name = ?
                     WHERE file_id = ?
                     """);
             PreparedStatement deleteRelation = connection.prepareStatement("""
                     DELETE FROM symbol_relations
                     WHERE project_id = ?
                     """)) {
            rename.setString(1, "RenamedTrigramService");
            rename.setString(2, "demo.RenamedTrigramService");
            rename.setLong(3, fileId);
            rename.executeUpdate();

            deleteRelation.setString(1, projectId.toString());
            deleteRelation.executeUpdate();

            assertTrue(pendingCount(connection, "symbol_search_pending") > 0);
            assertTrue(pendingCount(connection, "relation_search_pending") > 0);
            bumpGeneration(connection, projectId);
            assertEquals(0L, pendingCount(connection, "symbol_search_pending"));
            assertEquals(0L, pendingCount(connection, "relation_search_pending"));
        }

        assertTrue(repository.searchSymbols(projectId, "ScaleNeedle", 20).isEmpty());
        assertFalse(repository.searchSymbols(projectId, "Trigram", 20).isEmpty());
        assertTrue(repository.searchRelations(projectId, "TargetNeedle", 20).isEmpty());
    }

    @Test
    void canonicalRowsStayStagedUntilGenerationBoundary() throws Exception {
        SqliteDatabase database = database("generation-flush");
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            long fileId = insertProjectFile(connection, projectId);
            for (int index = 0; index < 5_000; index++) {
                String name = "BatchNeedle" + index;
                insertSymbol(connection, fileId, name, "demo." + name);
            }

            assertEquals(5_000L, pendingCount(connection, "symbol_search_pending"));
            assertEquals(0L, matchingCount(connection, "symbol_search_fts", "\"bat\" AND \"atc\" AND \"tch\""));

            bumpGeneration(connection, projectId);

            assertEquals(0L, pendingCount(connection, "symbol_search_pending"));
            assertEquals(5_000L, matchingCount(connection, "symbol_search_fts", "\"bat\" AND \"atc\" AND \"tch\""));
        }
    }

    @Test
    void rollbackRestoresCanonicalRowsAndFlushedProjection() throws Exception {
        SqliteDatabase database = database("projection-rollback");
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            long fileId = insertProjectFile(connection, projectId);
            insertSymbol(connection, fileId, "OriginalNeedle", "demo.OriginalNeedle");
            bumpGeneration(connection, projectId);

            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE symbols SET name = 'ReplacementToken', "
                    + "qualified_name = 'demo.ReplacementToken'");
            bumpGeneration(connection, projectId);
            assertEquals(1L, matchingCount(connection, "symbol_search_fts", "\"rep\""));
            assertEquals(0L, matchingCount(connection, "symbol_search_fts", "\"ori\""));
            connection.rollback();
        }

        // Une nouvelle connexion vérifie l'état réellement conservé sur disque.
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(0L, pendingCount(connection, "symbol_search_pending"));
            assertEquals(1L, matchingCount(connection, "symbol_search_fts", "\"ori\""));
            assertEquals(0L, matchingCount(connection, "symbol_search_fts", "\"rep\""));
            try (ResultSet row = statement.executeQuery("SELECT name FROM symbols")) {
                assertTrue(row.next());
                assertEquals("OriginalNeedle", row.getString(1));
            }
        }
    }

    @Test
    void twoCharacterQueriesKeepCompatibilityFallback() throws Exception {
        SqliteDatabase database = database("short-query");
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            long fileId = insertProjectFile(connection, projectId);
            insertSymbol(connection, fileId, "Id", "demo.Id");
            insertRelation(connection, projectId, fileId, "demo.Id", "db.Id");
        }

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        assertFalse(repository.searchSymbols(projectId, "id", 20).isEmpty());
        assertFalse(repository.searchRelations(projectId, "id", 20).isEmpty());
    }

    private SqliteDatabase database(String suffix) throws Exception {
        return new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve(suffix)));
    }

    private long insertProjectFile(Connection connection, UUID projectId) throws Exception {
        try (PreparedStatement project = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, ?, ?, 'LOCAL', ?, 'READY')
                """)) {
            project.setString(1, projectId.toString());
            project.setString(2, "project-" + projectId);
            project.setString(3, temporaryDirectory.resolve("project-" + projectId).toString());
            project.setString(4, Instant.EPOCH.toString());
            project.executeUpdate();
        }
        try (PreparedStatement file = connection.prepareStatement("""
                INSERT INTO indexed_files(
                    project_id, relative_path, language, size_bytes,
                    content_hash, modified_at, estimated_tokens, category)
                VALUES (?, 'src/Test.java', 'java', 64, 'hash', ?, 16, 'SOURCE')
                """)) {
            file.setString(1, projectId.toString());
            file.setString(2, Instant.EPOCH.toString());
            file.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id FROM indexed_files WHERE project_id = ?
                """)) {
            select.setString(1, projectId.toString());
            try (ResultSet resultSet = select.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static void insertSymbol(Connection connection, long fileId, String name, String qualifiedName)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbols(
                    file_id, kind, name, qualified_name, signature,
                    start_line, end_line, source_provider)
                VALUES (?, 'CLASS', ?, ?, ?, 1, 2, 'javaparser')
                """)) {
            statement.setLong(1, fileId);
            statement.setString(2, name);
            statement.setString(3, qualifiedName);
            statement.setString(4, "class " + name);
            statement.executeUpdate();
        }
    }

    private static void insertRelation(
            Connection connection,
            UUID projectId,
            long fileId,
            String source,
            String target) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbol_relations(
                    project_id, file_id, kind, source_ref, target_ref,
                    confidence, source_provider)
                VALUES (?, ?, 'USES', ?, ?, 1.0, 'javaparser')
                """)) {
            statement.setString(1, projectId.toString());
            statement.setLong(2, fileId);
            statement.setString(3, source);
            statement.setString(4, target);
            statement.executeUpdate();
        }
    }

    private static void bumpGeneration(Connection connection, UUID projectId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_index_generations(project_id, generation)
                VALUES (?, 1)
                ON CONFLICT(project_id) DO UPDATE SET generation = generation + 1
                """)) {
            statement.setString(1, projectId.toString());
            statement.executeUpdate();
        }
    }

    private static long pendingCount(Connection connection, String table) throws Exception {
        String sql = switch (table) {
            case "symbol_search_pending" -> "SELECT COUNT(*) FROM symbol_search_pending";
            case "relation_search_pending" -> "SELECT COUNT(*) FROM relation_search_pending";
            default -> throw new IllegalArgumentException("Unsupported pending table: " + table);
        };
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static long matchingCount(Connection connection, String table, String query) throws Exception {
        String sql = switch (table) {
            case "symbol_search_fts" -> "SELECT COUNT(*) FROM symbol_search_fts WHERE symbol_search_fts MATCH ?";
            case "relation_search_fts" -> "SELECT COUNT(*) FROM relation_search_fts WHERE relation_search_fts MATCH ?";
            default -> throw new IllegalArgumentException("Unsupported FTS table: " + table);
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, query);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static void assertVirtualIndexPlan(
            Connection connection,
            String table,
            String query) throws Exception {
        String sql = switch (table) {
            case "symbol_search_fts" -> """
                    EXPLAIN QUERY PLAN
                    SELECT rowid
                    FROM symbol_search_fts
                    WHERE symbol_search_fts MATCH ?
                    """;
            case "relation_search_fts" -> """
                    EXPLAIN QUERY PLAN
                    SELECT rowid
                    FROM relation_search_fts
                    WHERE relation_search_fts MATCH ?
                    """;
            default -> throw new IllegalArgumentException("Unsupported FTS table: " + table);
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, trigramQuery(query));
            try (ResultSet resultSet = statement.executeQuery()) {
                boolean usesVirtualIndex = false;
                while (resultSet.next()) {
                    String detail = resultSet.getString("detail");
                    usesVirtualIndex |= detail != null && detail.contains("VIRTUAL TABLE INDEX");
                }
                assertTrue(usesVirtualIndex, () -> table + " must use its FTS virtual index");
            }
        }
    }

    private static String trigramQuery(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        int[] codePoints = normalized.codePoints().toArray();
        StringBuilder query = new StringBuilder();
        for (int index = 0; index <= codePoints.length - 3; index++) {
            if (!query.isEmpty()) {
                query.append(" AND ");
            }
            String trigram = new String(codePoints, index, 3).replace("\"", "\"\"");
            query.append('\"').append(trigram).append('\"');
        }
        return query.toString();
    }

    private static void assertFuzzyIndexPlan(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                EXPLAIN QUERY PLAN
                SELECT id
                FROM symbols INDEXED BY idx_symbols_fuzzy_prefilter
                WHERE SUBSTR(LOWER(name), 1, 1) = ?
                  AND LENGTH(name) BETWEEN ? AND ?
                ORDER BY qualified_name
                LIMIT 20
                """)) {
            statement.setString(1, "a");
            statement.setInt(2, 5);
            statement.setInt(3, 40);
            try (ResultSet resultSet = statement.executeQuery()) {
                boolean usesIndex = false;
                while (resultSet.next()) {
                    String detail = resultSet.getString("detail");
                    usesIndex |= detail != null && detail.contains("idx_symbols_fuzzy_prefilter");
                }
                assertTrue(usesIndex, "Fuzzy prefilter must use idx_symbols_fuzzy_prefilter");
            }
        }
    }

    private static boolean versionAtLeast(String version, int major, int minor, int patch) {
        String[] parts = version.split("\\.");
        int actualMajor = Integer.parseInt(parts[0]);
        int actualMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int actualPatch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (actualMajor != major) {
            return actualMajor > major;
        }
        if (actualMinor != minor) {
            return actualMinor > minor;
        }
        return actualPatch >= patch;
    }
}
