package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteGraphQueryPlanTest {

    @TempDir
    Path tempDir;

    @Test
    void outgoingAndOwnerPlansSeekRequestedKeys() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(tempDir.resolve("outgoing-plan-home")));

        String outgoingPlan = queryPlan(
                database,
                SqliteGraphQueries.OUTGOING_GRAPH_SQL,
                "[\"src/Seed.java\"]", "project", "IMPORTS", 100);
        assertTrue(outgoingPlan.contains("project_id=? AND kind=? AND source_ref=?"), outgoingPlan);

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

    /** SQLite écrit {@code USING COVERING INDEX} quand l'index suffit à la requête. */
    private static void assertSeeksByIndex(String plan, String table) {
        assertTrue(plan.matches("(?s).*SEARCH " + table + " USING (COVERING )?INDEX.*"), plan);
        assertFalse(plan.contains("SCAN " + table), plan);
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
