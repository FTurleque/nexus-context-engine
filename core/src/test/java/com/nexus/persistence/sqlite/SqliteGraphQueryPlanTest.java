package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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
