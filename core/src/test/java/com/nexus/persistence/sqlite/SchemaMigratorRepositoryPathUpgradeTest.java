package com.nexus.persistence.sqlite;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.IndexStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorRepositoryPathUpgradeTest {
    @TempDir Path temporary;

    @Test void invalidatesCanonicalFactsAndReadinessThenRebuildsDerivedDocuments() throws Exception {
        var paths = new NexusPaths(temporary.resolve("home"));
        Path root = Files.createDirectory(temporary.resolve("project"));
        Files.writeString(root.resolve("Old.java"), "class LegacyMarker {}");
        java.util.UUID id;
        try (var app = NexusApplication.create(paths)) {
            id = app.registerProject(root, "upgrade").id();
            app.index(id, true, false);
        }
        var db = new SqliteDatabase(paths);
        var index = new SqliteIndexRepository(db);
        long generation = index.generation(id);
        // Simuler une base V006 existante avec corpus READY, faits et Lucene réels.
        try (var connection = db.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 7");
        }
        Files.delete(root.resolve("Old.java"));
        Files.writeString(root.resolve("New.java"), "class CurrentMarker {}");
        try (var app = NexusApplication.create(paths)) {
            var project = app.listProjects().getFirst();
            assertEquals(id, project.id());
            assertEquals(root.toRealPath(), project.rootPath());
            assertEquals(IndexStatus.NOT_INDEXED, project.indexStatus());
            assertNull(project.lastIndexedAt());
            assertTrue(index.findFiles(id).isEmpty());
            assertEquals(generation + 1, index.generation(id));
            try (var connection = db.openConnection(); var statement = connection.createStatement()) {
                for (String table : java.util.List.of("symbols", "symbol_relations")) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                    }
                }
            }
            assertThrows(IllegalStateException.class, () -> app.search(id, "LegacyMarker", 10, false));
            app.index(id, false, false);
            assertEquals(IndexStatus.READY, app.listProjects().getFirst().indexStatus());
            assertTrue(app.search(id, "LegacyMarker", 10, false).results().isEmpty());
            assertFalse(app.search(id, "CurrentMarker", 10, false).results().isEmpty());
        }
        long rebuilt = index.generation(id);
        new SqliteDatabase(paths);
        assertEquals(rebuilt, index.generation(id), "Migration appliquée une seule fois");
    }
}
