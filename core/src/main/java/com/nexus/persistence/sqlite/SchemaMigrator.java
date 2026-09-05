package com.nexus.persistence.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SchemaMigrator {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "db/migration/V001__initial_schema.sql"),
            new Migration(2, "db/migration/V002__index_generation.sql"),
            new Migration(3, "db/migration/V003__provider_and_graph_indexes.sql"),
            new Migration(4, "db/migration/V004__invalidate_invalid_symbol_ranges.sql"),
            new Migration(5, "db/migration/V005__enforce_symbol_range_constraints.sql"));

    private SchemaMigrator() {
    }

    static void migrate(Connection connection) throws SQLException, IOException {
        boolean initialAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable pendingFailure = null;
        try {
            ensureMigrationTable(connection);
            Map<Integer, String> appliedHashes = readAppliedHashes(connection);
            for (Migration migration : MIGRATIONS) {
                String currentHash = sha256(readResource(migration.resource()));
                if (!appliedHashes.containsKey(migration.version())) {
                    applyMigration(connection, migration, currentHash);
                    continue;
                }
                verifyOrBackfillHash(connection, migration, appliedHashes.get(migration.version()), currentHash);
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException exception) {
            pendingFailure = exception;
            rollbackPreserving(connection, exception);
            throw exception;
        } finally {
            restoreAutoCommitPreserving(connection, initialAutoCommit, pendingFailure);
        }
    }

    private static void ensureMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        script_name TEXT NOT NULL,
                        applied_at TEXT NOT NULL,
                        script_sha256 TEXT
                    )
                    """);
        }
        if (!hasSchemaMigrationsColumn(connection, "script_sha256")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE schema_migrations ADD COLUMN script_sha256 TEXT");
            }
        }
    }

    private static boolean hasSchemaMigrationsColumn(Connection connection, String column)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(schema_migrations)")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<Integer, String> readAppliedHashes(Connection connection) throws SQLException {
        Map<Integer, String> hashes = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT version, script_sha256 FROM schema_migrations")) {
            while (resultSet.next()) {
                hashes.put(resultSet.getInt("version"), resultSet.getString("script_sha256"));
            }
        }
        return hashes;
    }

    private static void verifyOrBackfillHash(
            Connection connection,
            Migration migration,
            String storedHash,
            String currentHash) throws SQLException {
        if (storedHash == null || storedHash.isBlank()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE schema_migrations SET script_sha256 = ? WHERE version = ?")) {
                statement.setString(1, currentHash);
                statement.setInt(2, migration.version());
                statement.executeUpdate();
            }
            return;
        }
        if (!storedHash.equals(currentHash)) {
            throw new IllegalStateException(
                    "Migration " + migration.version() + " (" + migration.resource() + ") a été "
                            + "modifiée après application : empreinte enregistrée " + storedHash
                            + ", empreinte actuelle " + currentHash
                            + ". La migration n'est jamais réappliquée silencieusement.");
        }
    }

    private static void applyMigration(Connection connection, Migration migration, String scriptHash)
            throws SQLException, IOException {
        String sql = readResource(migration.resource());
        for (String statementSql : SqlScriptSplitter.split(sql)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schema_migrations(version, script_name, applied_at, script_sha256)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.resource());
            statement.setString(3, Instant.now().toString());
            statement.setString(4, scriptHash);
            statement.executeUpdate();
        }
    }

    private static String sha256(String script) {
        String normalized = script.replace("\r\n", "\n").replace("\r", "\n");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponible sur cette JVM", exception);
        }
    }

    private static String readResource(String resource) throws IOException {
        ClassLoader classLoader = SchemaMigrator.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Migration introuvable : " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void rollbackPreserving(Connection connection, Throwable primaryFailure) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
            primaryFailure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommitPreserving(
            Connection connection,
            boolean initialAutoCommit,
            Throwable primaryFailure) throws SQLException {
        try {
            connection.setAutoCommit(initialAutoCommit);
        } catch (SQLException | RuntimeException restoreFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(restoreFailure);
                return;
            }
            throw restoreFailure;
        }
    }

    private record Migration(int version, String resource) {
    }
}
