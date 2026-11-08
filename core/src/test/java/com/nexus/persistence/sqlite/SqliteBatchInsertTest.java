package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteBatchInsertTest {

    private static final Path HELPER_SOURCE =
            Path.of("core/src/main/java/com/nexus/persistence/sqlite/SqliteBatchInsert.java");
    private static final Pattern SYMBOL_INSERT = Pattern.compile(
            "\\bINSERT\\s+(?:OR\\s+\\w+\\s+)?INTO\\s+[\"`\\[]?(symbols|symbol_relations)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSACTION_CONTROL = Pattern.compile(
            "\\.\\s*(?:commit|rollback|setAutoCommit|setSavepoint|releaseSavepoint)\\s*\\("
                    + "|\"\\s*(?:BEGIN|COMMIT|ROLLBACK|SAVEPOINT|RELEASE|END)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    @TempDir
    Path temporaryDirectory;

    @Test
    void limitsEachStatementTo128Rows() {
        assertEquals(128, SqliteBatchInsert.ROW_LIMIT, "Les lots d'écriture SQLite sont bornés à 128 lignes");
    }

    @Test
    void symbolInsertsGoOnlyThroughTheHelperWhichOwnsNoTransaction() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> javaSources = new ArrayList<>();
        for (Path root : List.of(Path.of("core/src/main/java"), Path.of("adapters"))) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> file.toString().replace('\\', '/').contains("/src/main/java/"))
                        .forEach(javaSources::add);
            }
        }
        assertTrue(javaSources.contains(HELPER_SOURCE), "SqliteBatchInsert.java introuvable : " + HELPER_SOURCE);
        for (Path source : javaSources) {
            if (source.equals(HELPER_SOURCE)) {
                continue;
            }
            Matcher insert = SYMBOL_INSERT.matcher(COMMENT.matcher(Files.readString(source)).replaceAll(" "));
            while (insert.find()) {
                violations.add(source + " : insertion dans " + insert.group(1) + " hors de SqliteBatchInsert");
            }
        }
        Matcher transaction = TRANSACTION_CONTROL.matcher(
                COMMENT.matcher(Files.readString(HELPER_SOURCE)).replaceAll(" "));
        while (transaction.find()) {
            violations.add(HELPER_SOURCE + " : contrôle de transaction « " + transaction.group().trim() + " »");
        }
        assertEquals(List.of(), violations);
    }

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
