package com.nexus.persistence.sqlite;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.IndexStatus;
import com.nexus.security.PrivateStoragePolicy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V019 invalide les dérivés construits sous {@code secret-redaction-v7} et conserve le registre. */
class SchemaMigratorTokenContextBoundUpgradeTest {

    /** Terme lexical distinctif porté par le corps des jetons. */
    private static final String LEAK_TERM = "ctxleakmarker";
    private static final int LEGACY_GENERATION = 11;

    @TempDir
    Path temporary;

    @Test
    void upgradesRealV018SchemaOnceAndRebuildsDerivedIndexesUnderTheNewPolicy() throws Exception {
        var paths = new NexusPaths(temporary.resolve("home"));
        PrivateStoragePolicy.ensurePrivateStorage(paths);
        Path root = Files.createDirectory(temporary.resolve("project"));
        // Jetons assemblés à l'exécution, ni masqués ni refusés sous v7 : un sk- de 300 caractères de
        // corps collé à un bloc de clé privée, un xoxb- court qui contient un tiret bas.
        String glued = "sk" + "-" + LEAK_TERM + "-" + "x".repeat(300 - LEAK_TERM.length() - 1);
        String underscore = "xo" + "xb-" + LEAK_TERM + "-abcdefghijkl_mnopqrstuv";
        Files.writeString(root.resolve("fixture.md"), "# TokenContextMarker\nLes clés sont dans secret.md et slack.md.\n");
        // Bloc de clé privée synthétique, marqueurs assemblés à l'exécution, sans corps de clé réel.
        String dashes = "-".repeat(5);
        String keyBlock = String.join("\n", dashes + "BEGIN RSA PRIVATE KEY" + dashes, "MII",
                dashes + "END RSA PRIVATE KEY" + dashes);
        Files.writeString(root.resolve("secret.md"), keyBlock + glued + "\n");
        Files.writeString(root.resolve("slack.md"), "SLACK = " + underscore + "\n");
        UUID id = UUID.randomUUID();
        createV018DatabaseWithReadyProject(paths, id, root);
        writeLegacyLuceneIndexContaining(paths, id, Map.of("secret.md", glued, "slack.md", underscore));
        var lexical = new com.nexus.search.lucene.LuceneSearchIndex(paths);
        assertFalse(lexical.search(id, LEAK_TERM, 10).isEmpty());

        try (var app = NexusApplication.create(paths)) {
            assertEquals(IndexStatus.NOT_INDEXED, app.getProject(id).indexStatus());
            assertNull(app.getProject(id).lastIndexedAt());
            assertEquals("token-context", app.getProject(id).name());
            assertThrows(IllegalStateException.class, () -> app.search(id, "TokenContextMarker", 10, false));
            var repository = new SqliteIndexRepository(new SqliteDatabase(paths));
            assertEquals(LEGACY_GENERATION + 1, repository.generation(id));

            var report = app.index(id, false, false).report();

            assertTrue(report.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.startsWith("INDEX_REDACTION_LIMIT") && diagnostic.contains("secret.md")),
                    report.diagnostics().toString());
            assertFalse(report.diagnostics().toString().contains(LEAK_TERM));
            assertTrue(lexical.search(id, LEAK_TERM, 10).isEmpty());
            assertTrue(app.search(id, LEAK_TERM, 10, false).results().isEmpty());
            var context = app.context(id, "TokenContextMarker", 2000, Set.of(), Map.of(), true);
            assertFalse(context.bundle().items().isEmpty());
            assertFalse(context.bundle().items().toString().contains(LEAK_TERM));
            long generation = repository.generation(id);
            new SqliteDatabase(paths);
            assertEquals(generation, repository.generation(id));
        }
    }

    @Test
    void freshDatabaseRecordsV019WithChecksum() throws Exception {
        var database = new SqliteDatabase(new NexusPaths(temporary.resolve("fresh")));
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT script_name, script_sha256 FROM schema_migrations WHERE version = 19")) {
            assertTrue(rows.next());
            assertEquals("db/migration/V019__invalidate_token_context_bound_indexes.sql", rows.getString(1));
            assertEquals(64, rows.getString(2).length());
        }
    }

    /** Base au schéma V018 réel, historique inclus, avec un projet {@code READY}. */
    private static void createV018DatabaseWithReadyProject(NexusPaths paths, UUID id, Path root) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.databaseFile())) {
            applyMigrationsUpToV018(connection);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO projects VALUES (?, 'token-context', ?, 'LOCAL', '2026-01-01T00:00:00Z', 'READY')")) {
                insert.setString(1, id.toString());
                insert.setString(2, root.toRealPath().toString());
                insert.executeUpdate();
            }
            try (var generation = connection.prepareStatement("INSERT INTO project_index_generations VALUES (?, ?)")) {
                generation.setString(1, id.toString());
                generation.setInt(2, LEGACY_GENERATION);
                generation.executeUpdate();
            }
        }
    }

    private static void applyMigrationsUpToV018(Connection connection) throws Exception {
        List<Path> migrations;
        var migrationResource = Objects.requireNonNull(
                SchemaMigratorTokenContextBoundUpgradeTest.class.getResource("/db/migration"),
                "Missing /db/migration test resource");
        try (var files = Files.list(Path.of(migrationResource.toURI()))) {
            migrations = files.filter(path -> path.getFileName().toString().matches("V0(?:0[1-9]|1[0-8])__.*\\.sql"))
                    .sorted().toList();
        }
        assertEquals(18, migrations.size());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations("
                    + "version INTEGER PRIMARY KEY, script_name TEXT NOT NULL, applied_at TEXT NOT NULL)");
        }
        try (var statement = connection.createStatement();
             var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?, ?, ?)")) {
            for (int index = 0; index < migrations.size(); index++) {
                for (String sql : SqlScriptSplitter.split(Files.readString(migrations.get(index), StandardCharsets.UTF_8))) {
                    statement.execute(sql);
                }
                insert.setInt(1, index + 1);
                insert.setString(2, "db/migration/" + migrations.get(index).getFileName());
                insert.setString(3, "2026-01-01T00:00:00Z");
                insert.executeUpdate();
            }
        }
    }

    /** Index Lucene construit sous {@code secret-redaction-v7} : les jetons y figurent en clair. */
    private static void writeLegacyLuceneIndexContaining(NexusPaths paths, UUID id, Map<String, String> contents)
            throws Exception {
        PrivateStoragePolicy.ensurePrivateDirectory(paths, paths.projectLuceneIndex(id));
        try (var directory = FSDirectory.open(paths.projectLuceneIndex(id));
             var analyzer = new StandardAnalyzer();
             var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (var entry : contents.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                var document = new Document();
                document.add(new StringField("path", entry.getKey(), Field.Store.YES));
                document.add(new StringField("language", "markdown", Field.Store.YES));
                document.add(new StringField("category", "DOCUMENTATION", Field.Store.YES));
                document.add(new TextField("content", entry.getValue(), Field.Store.NO));
                writer.addDocument(document);
            }
        }
    }
}
