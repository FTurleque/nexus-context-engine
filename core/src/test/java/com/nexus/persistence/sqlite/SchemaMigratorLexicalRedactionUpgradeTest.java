package com.nexus.persistence.sqlite;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorLexicalRedactionUpgradeTest {

    @Test
    void invalidatesExistingProjectsAndBumpsGeneration() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE projects (
                        id TEXT PRIMARY KEY,
                        index_status TEXT NOT NULL,
                        last_indexed_at TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE project_index_generations (
                        project_id TEXT PRIMARY KEY,
                        generation INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "INSERT INTO projects(id, index_status, last_indexed_at) VALUES "
                            + "('ready-project', 'READY', '2026-09-01T10:00:00Z'),"
                            + "('pending-project', 'NOT_INDEXED', NULL)");
            statement.executeUpdate(
                    "INSERT INTO project_index_generations(project_id, generation) VALUES "
                            + "('ready-project', 7)");

            String migration = resource("db/migration/V006__invalidate_unredacted_lexical_indexes.sql");
            for (String sql : SqlScriptSplitter.split(migration)) {
                statement.execute(sql);
            }

            try (ResultSet projects = statement.executeQuery(
                    "SELECT id, index_status, last_indexed_at FROM projects ORDER BY id")) {
                assertTrue(projects.next());
                assertEquals("pending-project", projects.getString("id"));
                assertEquals("NOT_INDEXED", projects.getString("index_status"));
                assertNull(projects.getString("last_indexed_at"));
                assertTrue(projects.next());
                assertEquals("ready-project", projects.getString("id"));
                assertEquals("NOT_INDEXED", projects.getString("index_status"));
                assertNull(projects.getString("last_indexed_at"));
            }

            try (ResultSet generations = statement.executeQuery(
                    "SELECT project_id, generation FROM project_index_generations ORDER BY project_id")) {
                assertTrue(generations.next());
                assertEquals("pending-project", generations.getString("project_id"));
                assertEquals(1, generations.getInt("generation"));
                assertTrue(generations.next());
                assertEquals("ready-project", generations.getString("project_id"));
                assertEquals(8, generations.getInt("generation"));
            }
        }
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = SchemaMigratorLexicalRedactionUpgradeTest.class
                .getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Ressource de migration introuvable : " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
