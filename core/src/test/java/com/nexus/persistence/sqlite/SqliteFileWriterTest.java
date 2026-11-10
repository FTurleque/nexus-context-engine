package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Publication des analyses en attente : identifiant par RETURNING et instructions préparées par transaction. */
class SqliteFileWriterTest {

    /** Plus d'un lot complet de 128 lignes, pour exercer l'instruction pleine et l'instruction de fin. */
    private static final int SYMBOLS_PER_FILE = 130;
    private static final int RELATIONS_PER_FILE = 2;

    @TempDir
    Path temporaryDirectory;

    @Test
    void preparedStatementCountDoesNotGrowWithTheNumberOfFiles() throws Exception {
        List<String> fewFiles = preparedSql("few", 2);
        List<String> manyFiles = preparedSql("many", 40);

        assertEquals(fewFiles.size(), manyFiles.size(),
                () -> "few=" + fewFiles.size() + " many=" + manyFiles.size());
        assertEquals(fewFiles, manyFiles);
    }

    @Test
    void fileIdentifierComesFromTheUpsertNotFromASecondQuery() throws Exception {
        List<String> sql = preparedSql("returning", 3);

        assertTrue(sql.stream().anyMatch(statement ->
                statement.contains("ON CONFLICT(project_id, relative_path)") && statement.contains("RETURNING id")),
                sql.toString());
        assertTrue(sql.stream().noneMatch(statement ->
                statement.contains("SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?")),
                sql.toString());
    }

    @Test
    void reindexingExistingFilesKeepsTheirIdentifiersAndReplacesTheAnalysis() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("reindex")));
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            insertProject(connection, projectId);
            stageAndPublish(connection, connection, projectId, updates(3, "first"));
            connection.commit();
            List<Long> identifiers = fileIds(connection, projectId);
            assertEquals(3, identifiers.size());

            stageAndPublish(connection, connection, projectId, updates(3, "second"));
            connection.commit();

            assertEquals(identifiers, fileIds(connection, projectId));
            assertEquals(3L * SYMBOLS_PER_FILE, count(connection, "symbols"));
            assertEquals(3L * RELATIONS_PER_FILE, count(connection, "symbol_relations"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM symbols WHERE name LIKE 'first%'");
                 ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1));
            }
        }
    }

    private List<String> preparedSql(String name, int fileCount) throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve(name)));
        UUID projectId = UUID.randomUUID();
        List<String> prepared = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            insertProject(connection, projectId);
            Connection counting = recordingConnection(connection, prepared);
            stageAndPublish(connection, counting, projectId, updates(fileCount, "file"));
            connection.commit();
            assertEquals((long) fileCount * SYMBOLS_PER_FILE, count(connection, "symbols"));
            assertEquals((long) fileCount * RELATIONS_PER_FILE, count(connection, "symbol_relations"));
        }
        return prepared;
    }

    /** Met les analyses en attente puis les publie ; seule la publication passe par {@code publishing}. */
    private static void stageAndPublish(Connection connection, Connection publishing, UUID projectId,
            List<IndexedFileUpdate> updates) throws Exception {
        SqliteIndexStaging.stage(connection, projectId, updates);
        SqliteFileWriter.writeStagedChanges(publishing, projectId, Set.of());
    }

    /** Enveloppe une connexion pour enregistrer le SQL de chaque {@code prepareStatement}. */
    private static Connection recordingConnection(Connection delegate, List<String> prepared) {
        return (Connection) Proxy.newProxyInstance(
                SqliteFileWriterTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        prepared.add(((String) arguments[0]).strip());
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private List<IndexedFileUpdate> updates(int fileCount, String prefix) {
        List<IndexedFileUpdate> updates = new ArrayList<>();
        for (int file = 0; file < fileCount; file++) {
            String relativePath = String.format(Locale.ROOT, "src/File%03d.java", file);
            Path absolute = temporaryDirectory.resolve("sources").resolve(relativePath);
            List<CodeSymbol> symbols = new ArrayList<>();
            for (int symbol = 0; symbol < SYMBOLS_PER_FILE; symbol++) {
                String name = prefix + "Symbol" + file + "x" + symbol;
                symbols.add(new CodeSymbol(SymbolKind.CLASS, name, "demo." + name, "class " + name, 1, 2));
            }
            List<SymbolRelation> relations = new ArrayList<>();
            for (int relation = 0; relation < RELATIONS_PER_FILE; relation++) {
                relations.add(new SymbolRelation(RelationKind.USES, "demo." + prefix + file, "demo.Target" + relation));
            }
            updates.add(new IndexedFileUpdate(
                    new ScannedFile(absolute, relativePath, "java", 10, prefix + "-hash-" + file,
                            Instant.EPOCH, 5, FileCategory.SOURCE),
                    new AnalysisResult(absolute, "java", symbols, relations)));
        }
        return updates;
    }

    private static void insertProject(Connection connection, UUID projectId) throws Exception {
        try (PreparedStatement project = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, 'writer-test', ?, 'LOCAL', NULL, 'READY')
                """)) {
            project.setString(1, projectId.toString());
            project.setString(2, "/writer-test-" + projectId);
            project.executeUpdate();
        }
    }

    private static List<Long> fileIds(Connection connection, UUID projectId) throws Exception {
        List<Long> identifiers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM indexed_files WHERE project_id = ? ORDER BY relative_path")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    identifiers.add(rows.getLong(1));
                }
            }
        }
        assertFalse(identifiers.isEmpty());
        return identifiers;
    }

    private static long count(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
