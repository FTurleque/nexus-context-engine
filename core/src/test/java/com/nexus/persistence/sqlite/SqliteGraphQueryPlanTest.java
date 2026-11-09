package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteGraphQueryPlanTest {

    @TempDir
    Path tempDir;

    @Test
    void outgoingAndOwnerPlansSeekRequestedKeys() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("outgoing-plan-home")));

        String firstPagePlan = queryPlan(
                database,
                SqliteGraphQueries.OUTGOING_GRAPH_SQL,
                "[\"src/Seed.java\"]", "project", "IMPORTS", null, null, 100);
        assertTrue(firstPagePlan.contains("project_id=? AND kind=? AND source_ref=?"), firstPagePlan);

        String nextPagePlan = queryPlan(
                database,
                SqliteGraphQueries.OUTGOING_GRAPH_SQL,
                "[\"src/Seed.java\"]", "project", "IMPORTS", "src/Seed.java", "demo.Last", 100);
        assertTrue(nextPagePlan.contains("project_id=? AND kind=? AND source_ref=?"), nextPagePlan);

        String ownerPlan = queryPlan(
                database,
                SqliteGraphQueries.TARGET_TYPE_OWNERS_SQL,
                "[\"demo.Seed\"]", "project");
        assertTrue(ownerPlan.contains("idx_symbols_qualified_name (qualified_name=?)"), ownerPlan);
    }

    @Test
    void incomingPlanUsesTargetEqualityAndTargetRange() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("incoming-plan-home")));
        String plan = queryPlan(
                database,
                SqliteGraphQueries.INCOMING_GRAPH_SQL,
                "[\"src/Seed.java\"]", "project", "project", "IMPORTS", 100);

        assertTrue(plan.contains("project_id=? AND kind=? AND target_ref=?"), plan);
        assertTrue(plan.contains("project_id=? AND kind=? AND target_ref>? AND target_ref<?"), plan);
    }

    @Test
    void perFileRelationDeletionSeeksByFileAndProvider() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("delete-plan-home")));

        String plan = queryPlan(
                database,
                "DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?",
                1L, "javaparser");

        assertSeeksByIndex(plan, "symbol_relations");
        assertTrue(plan.contains("idx_symbol_relations_file_provider (file_id=? AND source_provider=?)"), plan);
    }

    @Test
    void foreignKeyCascadeProbeSeeksByFile() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("cascade-plan-home")));

        String plan = queryPlan(
                database,
                "SELECT 1 FROM symbol_relations WHERE file_id = ?",
                1L);

        assertSeeksByIndex(plan, "symbol_relations");
        assertTrue(plan.contains("(file_id=?)"), plan);
    }

    /** Index préfixes stricts d'autres index (AUD-PRF-15), supprimés par la migration V013. */
    private static final List<String> REDUNDANT_PREFIX_INDEXES = List.of(
            "idx_symbol_relations_project", "idx_indexed_files_project", "idx_symbols_file");

    /** Requêtes de production servies avant V013 par un index préfixe : libellé, SQL, alias du plan, paramètres. */
    private static final List<PlanCase> PREFIX_INDEX_QUERIES = List.of(
            // SqliteFileQueries.findFiles
            new PlanCase("findFiles", "indexed_files", """
                    SELECT id, project_id, relative_path, language, size_bytes,
                           content_hash, modified_at, estimated_tokens, category
                    FROM indexed_files WHERE project_id = ? ORDER BY relative_path
                    """, "project"),
            // SqliteFileQueries.statistics et SqliteProviderWriter.findFileIds
            new PlanCase("files count", "indexed_files",
                    "SELECT COUNT(*) FROM indexed_files WHERE project_id = ?", "project"),
            new PlanCase("file ids", "indexed_files",
                    "SELECT id, relative_path FROM indexed_files WHERE project_id = ?", "project"),
            // cascade de clé étrangère projects -> indexed_files
            new PlanCase("files cascade", "indexed_files",
                    "SELECT 1 FROM indexed_files WHERE project_id = ?", "project"),
            // SqliteRelationQueries.findRelations et findImportRelations
            new PlanCase("findRelations", "symbol_relations", """
                    SELECT kind, source_ref, target_ref, confidence, source_provider
                    FROM symbol_relations WHERE project_id = ?
                    ORDER BY source_ref, kind, target_ref, source_provider
                    """, "project"),
            new PlanCase("findImportRelations", "symbol_relations", """
                    SELECT kind, source_ref, target_ref, confidence, source_provider
                    FROM symbol_relations WHERE project_id = ? AND kind = ?
                    """, "project", "IMPORTS"),
            // SqliteFileQueries.statistics
            new PlanCase("relations count", "symbol_relations",
                    "SELECT COUNT(*) FROM symbol_relations WHERE project_id = ?", "project"),
            // SqliteProviderWriter.deleteProviderData
            new PlanCase("provider relations delete", "symbol_relations",
                    "DELETE FROM symbol_relations WHERE project_id = ? AND source_provider = ?",
                    "project", "scip"),
            // cascade de clé étrangère projects -> symbol_relations
            new PlanCase("relations cascade", "symbol_relations",
                    "SELECT 1 FROM symbol_relations WHERE project_id = ?", "project"),
            // SqliteFileQueries.findSymbols : s est parcouru par f.id = s.file_id
            new PlanCase("findSymbols", "s", """
                    SELECT f.relative_path, s.kind, s.name, s.qualified_name
                    FROM symbols s JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ?
                    """, "project"),
            new PlanCase("symbols count", "s", """
                    SELECT COUNT(*) FROM symbols s JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ?
                    """, "project"),
            // SqliteWriter : suppression des symboles d'un fichier
            new PlanCase("file symbols delete", "symbols",
                    "DELETE FROM symbols WHERE file_id = ? AND source_provider = ?", 1L, "javaparser"),
            // cascade de clé étrangère indexed_files -> symbols
            new PlanCase("symbols cascade", "symbols",
                    "SELECT 1 FROM symbols WHERE file_id = ?", 1L));

    @Test
    void redundantPrefixIndexesAreAbsentFromTheMigratedSchema() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("prefix-absent-home")));
        try (Connection connection = database.openConnection()) {
            List<String> indexes = new ArrayList<>();
            for (String table : List.of("symbol_relations", "indexed_files", "symbols")) {
                indexes.addAll(SchemaMigratorFileIndexUpgradeTest.indexNames(connection, table));
            }
            for (String redundant : REDUNDANT_PREFIX_INDEXES) {
                assertFalse(indexes.contains(redundant), redundant + " doit être supprimé : " + indexes);
            }
        }
    }

    @Test
    void noIndexIsAStrictPrefixOfAnotherIndexOfTheSameTable() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("prefix-scan-home")));
        try (Connection connection = database.openConnection()) {
            for (String table : List.of("symbol_relations", "indexed_files", "symbols")) {
                Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
                for (String index : SchemaMigratorFileIndexUpgradeTest.indexNames(connection, table)) {
                    columnsByIndex.put(index, SchemaMigratorFileIndexUpgradeTest.indexColumns(connection, index));
                }
                columnsByIndex.forEach((index, columns) -> columnsByIndex.forEach((other, otherColumns) ->
                        assertFalse(!index.equals(other)
                                        && columns.size() < otherColumns.size()
                                        && otherColumns.subList(0, columns.size()).equals(columns),
                                index + " est un préfixe strict de " + other + " sur " + table)));
            }
        }
    }

    @Test
    void projectAndFileQueriesStayIndexedOnceThePrefixIndexesAreDropped() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("prefix-plans-home")));
        // Idempotent : prouve les plans sans les index préfixes, que la migration V013 les ait déjà supprimés ou non.
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            for (String redundant : REDUNDANT_PREFIX_INDEXES) {
                statement.execute("DROP INDEX IF EXISTS " + redundant);
            }
        }

        for (PlanCase planCase : PREFIX_INDEX_QUERIES) {
            String plan = queryPlan(database, planCase.sql(), planCase.parameters());
            assertSeeksByIndex(plan, planCase.planName());
            for (String redundant : REDUNDANT_PREFIX_INDEXES) {
                // Frontière de mot : idx_symbol_relations_project préfixe le nom d'autres index.
                assertFalse(Pattern.compile("\\b" + redundant + "\\b").matcher(plan).find(),
                        planCase.label() + " : " + plan);
            }
        }
    }

    /** SQLite écrit {@code USING COVERING INDEX} quand l'index suffit à la requête. */
    private static void assertSeeksByIndex(String plan, String table) {
        assertTrue(plan.matches("(?s).*SEARCH " + table + " USING (COVERING )?INDEX.*"), plan);
        assertFalse(plan.matches("(?s).*SCAN " + table + "\\b.*"), plan);
    }

    private record PlanCase(String label, String planName, String sql, Object... parameters) {
    }

    private static String queryPlan(SqliteDatabase database, String sql, Object... parameters) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement query = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            for (int index = 0; index < parameters.length; index++) {
                query.setObject(index + 1, parameters[index]);
            }
            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    plan.append(rows.getString("detail")).append('\n');
                }
            }
            return plan.toString();
        }
    }
}
