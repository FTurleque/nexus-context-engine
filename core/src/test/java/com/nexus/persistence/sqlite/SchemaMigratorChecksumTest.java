package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression tests for P3: applied migrations carry a SHA-256 fingerprint so that a historical
 * script modified after the fact is detected and the database opens fail-closed, while pre-hash
 * bases (upgraded from an earlier schema) are backfilled transparently.
 */
class SchemaMigratorChecksumTest {

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;

    @BeforeEach
    void setUp() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        database = new SqliteDatabase(paths); // exécute la migration initiale avec empreintes
    }

    @Test
    void migrationsStoreASha256Fingerprint() throws Exception {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT version, script_sha256 FROM schema_migrations ORDER BY version")) {
            int rows = 0;
            while (resultSet.next()) {
                rows++;
                String hash = resultSet.getString("script_sha256");
                assertNotNull(hash, "empreinte enregistrée pour la migration " + resultSet.getInt("version"));
                assertEquals(64, hash.length(), "SHA-256 hex sur 64 caractères");
            }
            assertTrue(rows >= 2, "au moins les deux migrations initiales sont enregistrées");
        }
    }

    @Test
    void reRunningMigrationsWithUnchangedScriptsSucceeds() throws Exception {
        // Même fichier, même hash → idempotent, aucune erreur.
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(true);
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
        }
    }

    @Test
    void tamperedHistoricalMigrationIsDetectedAndFailsClosed() throws Exception {
        // Simule un script historique modifié après application : l'empreinte enregistrée diverge.
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE schema_migrations SET script_sha256 = 'tampered' WHERE version = 1");
        }
        try (Connection connection = database.openConnection()) {
            IllegalStateException exception =
                    assertThrows(IllegalStateException.class, () -> SchemaMigrator.migrate(connection));
            assertTrue(exception.getMessage().contains("modifiée après application"),
                    "message fail-closed explicite");
        }
    }

    @Test
    void preHashBaseIsBackfilledWithoutError() throws Exception {
        // Simule une base pré-hash : la colonne existe mais la valeur est NULL.
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "UPDATE schema_migrations SET script_sha256 = NULL WHERE version = 1");
        }
        try (Connection connection = database.openConnection()) {
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
        }
        // Après backfill, l'empreinte est renseignée.
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT script_sha256 FROM schema_migrations WHERE version = 1")) {
            assertTrue(resultSet.next());
            String hash = resultSet.getString("script_sha256");
            assertNotNull(hash, "empreinte backfillée");
            assertEquals(64, hash.length());
        }
    }

    @Test
    void legacyTableWithoutHashColumnIsUpgradedAdditively() throws Exception {
        // Une vraie base V002, sans objets introduits par les migrations suivantes.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            for (String resource : new String[] {
                    "db/migration/V001__initial_schema.sql",
                    "db/migration/V002__index_generation.sql"}) {
                try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(input, resource);
                    String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    for (String migrationStatement : SqlScriptSplitter.split(sql)) {
                        statement.execute(migrationStatement);
                    }
                }
            }
            statement.executeUpdate("""
                    CREATE TABLE schema_migrations (
                        version INTEGER PRIMARY KEY,
                        script_name TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "INSERT INTO schema_migrations(version, script_name, applied_at) "
                            + "VALUES (1, 'db/migration/V001__initial_schema.sql', '2020-01-01T00:00:00Z'),"
                            + "       (2, 'db/migration/V002__index_generation.sql', '2020-01-01T00:00:00Z')");
            // La colonne est ajoutée additivement puis backfillée : aucune erreur.
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
            try (ResultSet rows = statement.executeQuery(
                    "SELECT script_sha256 FROM schema_migrations WHERE version IN (1, 2) ORDER BY version")) {
                for (int version = 1; version <= 2; version++) {
                    assertTrue(rows.next());
                    assertEquals(64, rows.getString(1).length());
                }
            }
            assertDoesNotThrow(() -> SchemaMigrator.migrate(connection));
        }
    }
}
