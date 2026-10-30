package com.nexus.persistence.sqlite;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.IndexStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** V011 invalide les dérivés construits sous {@code secret-redaction-v5} et conserve le registre. */
class SchemaMigratorSecretFormatUpgradeTest {

    private static final String TOKEN_VALUE = "tok0123456789formatsecret";

    @TempDir
    Path temporary;

    @Test
    void upgradesRealV010SchemaOnceAndRebuildsDerivedIndexesUnderTheNewPolicy() throws Exception {
        var paths = new NexusPaths(temporary.resolve("home"));
        paths.ensurePrivateStorage();
        Path root = Files.createDirectory(temporary.resolve("project"));
        Files.writeString(root.resolve("fixture.md"), "# FormatMarker\ntoken=" + TOKEN_VALUE + "\n");
        UUID id = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.databaseFile());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, script_name TEXT NOT NULL, applied_at TEXT NOT NULL)");
            List<Path> migrations;
            var migrationResource = Objects.requireNonNull(
                    getClass().getResource("/db/migration"), "Missing /db/migration test resource");
            try (var files = Files.list(Path.of(migrationResource.toURI()))) {
                migrations = files.filter(path -> path.getFileName().toString().matches("V0(?:0[1-9]|10)__.*\\.sql"))
                        .sorted().toList();
            }
            assertEquals(10, migrations.size());
            for (int index = 0; index < migrations.size(); index++) {
                for (String sql : SqlScriptSplitter.split(Files.readString(migrations.get(index), StandardCharsets.UTF_8))) {
                    statement.execute(sql);
                }
                try (var insert = connection.prepareStatement("INSERT INTO schema_migrations VALUES (?, ?, ?)")) {
                    insert.setInt(1, index + 1);
                    insert.setString(2, "db/migration/" + migrations.get(index).getFileName());
                    insert.setString(3, "2026-01-01T00:00:00Z");
                    insert.executeUpdate();
                }
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO projects VALUES (?, 'formats', ?, 'LOCAL', '2026-01-01T00:00:00Z', 'READY')")) {
                insert.setString(1, id.toString());
                insert.setString(2, root.toRealPath().toString());
                insert.executeUpdate();
            }
            try (var generation = connection.prepareStatement("INSERT INTO project_index_generations VALUES (?, ?)")) {
                generation.setString(1, id.toString());
                generation.setInt(2, 41);
                generation.executeUpdate();
            }
        }
        // Un index Lucene construit sous secret-redaction-v5 contient le jeton en clair.
        paths.ensurePrivateDirectory(paths.projectLuceneIndex(id));
        try (var directory = org.apache.lucene.store.FSDirectory.open(paths.projectLuceneIndex(id));
             var analyzer = new org.apache.lucene.analysis.standard.StandardAnalyzer();
             var writer = new org.apache.lucene.index.IndexWriter(directory,
                     new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
            var document = new org.apache.lucene.document.Document();
            document.add(new org.apache.lucene.document.StringField("path", "fixture.md", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.StringField("language", "markdown", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.StringField("category", "DOCUMENTATION", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.TextField("content", TOKEN_VALUE, org.apache.lucene.document.Field.Store.NO));
            writer.addDocument(document);
        }
        var lexical = new com.nexus.search.lucene.LuceneSearchIndex(paths);
        assertFalse(lexical.search(id, TOKEN_VALUE, 10).isEmpty());

        try (var app = NexusApplication.create(paths)) {
            assertEquals(IndexStatus.NOT_INDEXED, app.getProject(id).indexStatus());
            assertNull(app.getProject(id).lastIndexedAt());
            assertEquals("formats", app.getProject(id).name());
            assertThrows(IllegalStateException.class, () -> app.search(id, "FormatMarker", 10, false));
            var repository = new SqliteIndexRepository(new SqliteDatabase(paths));
            assertEquals(42, repository.generation(id));

            app.index(id, false, false);

            assertTrue(lexical.search(id, TOKEN_VALUE, 10).isEmpty());
            assertTrue(app.search(id, TOKEN_VALUE, 10, false).results().isEmpty());
            var context = app.context(id, "FormatMarker", 2000, Set.of(), Map.of(), true);
            assertFalse(context.bundle().items().isEmpty());
            assertFalse(context.bundle().items().toString().contains(TOKEN_VALUE));
            long generation = repository.generation(id);
            new SqliteDatabase(paths);
            assertEquals(generation, repository.generation(id));
        }
    }

    @Test
    void freshDatabaseRecordsV011WithChecksum() throws Exception {
        var database = new SqliteDatabase(new NexusPaths(temporary.resolve("fresh")));
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT script_sha256 FROM schema_migrations WHERE version = 11")) {
            assertTrue(rows.next());
            assertEquals(64, rows.getString(1).length());
        }
    }
}
