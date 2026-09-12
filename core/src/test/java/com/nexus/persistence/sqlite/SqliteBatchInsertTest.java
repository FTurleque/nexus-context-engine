package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteBatchInsertTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void executesFullAndTailRowsWithBoundValues() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("batch")));
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            long fileId = insertProjectAndFile(connection, projectId);

            try (SqliteBatchInsert symbols =
                         new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS)) {
                for (int index = 0; index < 129; index++) {
                    String name = index == 128 ? "tail ' value" : "Batch" + index;
                    symbols.add(fileId, "CLASS", name, "demo." + name, "class " + name,
                            index + 1, index + 1, "test-provider");
                }
                symbols.flush();
            }
            connection.commit();

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*), MAX(name) FROM symbols WHERE source_provider = ?")) {
                statement.setString(1, "test-provider");
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(129, rows.getInt(1));
                    assertEquals("tail ' value", rows.getString(2));
                }
            }
        }
    }

    @Test
    void rejectsWrongColumnCountAndUseAfterClose() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("guards")));
        try (Connection connection = database.openConnection();
             SqliteBatchInsert symbols =
                     new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS)) {
            assertThrows(IllegalArgumentException.class, () -> symbols.add(1L));
            symbols.close();
            assertThrows(java.sql.SQLException.class,
                    () -> symbols.add(1L, "CLASS", "name", "qualified", "signature", 1, 1, "test"));
        }
    }

    private static long insertProjectAndFile(Connection connection, UUID projectId) throws Exception {
        try (PreparedStatement project = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, 'batch-test', '/batch-test', 'LOCAL', NULL, 'READY')
                """)) {
            project.setString(1, projectId.toString());
            project.executeUpdate();
        }
        try (PreparedStatement file = connection.prepareStatement("""
                INSERT INTO indexed_files(
                    project_id, relative_path, language, size_bytes,
                    content_hash, modified_at, estimated_tokens, category)
                VALUES (?, 'src/Test.java', 'java', 1, 'hash', '2026-09-12T00:00:00Z', 1, 'SOURCE')
                """)) {
            file.setString(1, projectId.toString());
            file.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM indexed_files WHERE project_id = ?")) {
            select.setString(1, projectId.toString());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("file fixture was not inserted");
                }
                return rows.getLong(1);
            }
        }
    }
}
