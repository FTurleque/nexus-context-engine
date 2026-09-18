package com.nexus.persistence.sqlite;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.IndexStatus;
import com.nexus.security.PublicContextPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorScalarSecretUpgradeTest {
    @TempDir Path temporary;

    @Test
    void upgradesRealV009SchemaOnceAndRebuildsPublicContent() throws Exception {
        var paths = new NexusPaths(temporary.resolve("home"));
        paths.ensurePrivateStorage();
        Path root = Files.createDirectory(temporary.resolve("project"));
        Files.writeString(root.resolve("fixture.md"),
                "# PrivacyMarker\r\n{\"password\": 123456789, \"api_key\": synthetic-unquoted-secret-123456}\r\n");
        UUID id = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + paths.databaseFile());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, script_name TEXT NOT NULL, applied_at TEXT NOT NULL)");
            List<Path> migrations;
            try (var files = Files.list(Path.of(getClass().getResource("/db/migration").toURI()))) {
                migrations = files.filter(path -> path.getFileName().toString().matches("V00[1-9]__.*\\.sql"))
                        .sorted().toList();
            }
            assertEquals(9, migrations.size());
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
            try (var insert = connection.prepareStatement("INSERT INTO projects VALUES (?, 'privacy', ?, 'LOCAL', '2026-01-01T00:00:00Z', 'READY')")) {
                insert.setString(1, id.toString());
                insert.setString(2, root.toRealPath().toString());
                insert.executeUpdate();
            }
            try (var generation = connection.prepareStatement(
                    "INSERT INTO project_index_generations VALUES (?, ?)")) {
                generation.setString(1, id.toString());
                generation.setInt(2, 17);
                generation.executeUpdate();
            }
        }
        // Un ancien index Lucene contient réellement le terme sensible avant l’upgrade.
        paths.ensurePrivateDirectory(paths.projectLuceneIndex(id));
        try (var directory = org.apache.lucene.store.FSDirectory.open(paths.projectLuceneIndex(id));
             var analyzer = new org.apache.lucene.analysis.standard.StandardAnalyzer();
             var writer = new org.apache.lucene.index.IndexWriter(directory,
                     new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
            var document = new org.apache.lucene.document.Document();
            document.add(new org.apache.lucene.document.StringField("path", "fixture.md", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.StringField("language", "markdown", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.StringField("category", "DOCUMENTATION", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.TextField("content", "123456789", org.apache.lucene.document.Field.Store.NO));
            writer.addDocument(document);
        }
        var lexical = new com.nexus.search.lucene.LuceneSearchIndex(paths);
        assertFalse(lexical.search(id, "123456789", 10).isEmpty());
        try (var app = NexusApplication.create(paths)) {
            assertEquals(IndexStatus.NOT_INDEXED, app.getProject(id).indexStatus());
            assertNull(app.getProject(id).lastIndexedAt());
            assertThrows(IllegalStateException.class, () -> app.search(id, "PrivacyMarker", 10, false));
            var repository = new SqliteIndexRepository(new SqliteDatabase(paths));
            assertEquals(18, repository.generation(id));
            app.index(id, false, false);
            assertTrue(lexical.search(id, "123456789", 10).isEmpty());
            assertTrue(app.search(id, "123456789", 10, false).results().isEmpty());
            assertTrue(app.search(id, "synthetic-unquoted-secret-123456", 10, false).results().isEmpty());
            var context = app.context(id, "PrivacyMarker", 2000, Set.of(), Map.of(), true);
            var exposed = PublicContextPolicy.expose(context.project(), context.bundle(), context.query());
            assertFalse(exposed.items().isEmpty());
            String content = exposed.items().toString();
            assertFalse(content.contains("123456789"));
            assertFalse(content.contains("synthetic-unquoted-secret-123456"));
            assertTrue(content.contains("[REDACTED]"));
            long generation = repository.generation(id);
            new SqliteDatabase(paths);
            assertEquals(generation, repository.generation(id));
        }
    }

    @Test
    void freshDatabaseRecordsV010WithChecksum() throws Exception {
        var database = new SqliteDatabase(new NexusPaths(temporary.resolve("fresh")));
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT script_sha256 FROM schema_migrations WHERE version = 10")) {
            assertTrue(rows.next());
            assertEquals(64, rows.getString(1).length());
        }
    }
}
