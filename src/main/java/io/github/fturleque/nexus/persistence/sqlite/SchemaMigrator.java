package io.github.fturleque.nexus.persistence.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SchemaMigrator {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "db/migration/V001__initial_schema.sql"));

    private SchemaMigrator() {
    }

    static void migrate(Connection connection) throws SQLException, IOException {
        boolean initialAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ensureMigrationTable(connection);
            Set<Integer> appliedVersions = readAppliedVersions(connection);
            for (Migration migration : MIGRATIONS) {
                if (!appliedVersions.contains(migration.version())) {
                    applyMigration(connection, migration);
                }
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(initialAutoCommit);
        }
    }

    private static void ensureMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        script_name TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static Set<Integer> readAppliedVersions(Connection connection) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT version FROM schema_migrations")) {
            while (resultSet.next()) {
                versions.add(resultSet.getInt(1));
            }
        }
        return versions;
    }

    private static void applyMigration(Connection connection, Migration migration)
            throws SQLException, IOException {
        String sql = readResource(migration.resource());
        for (String statementSql : splitStatements(sql)) {
            if (!statementSql.isBlank()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(statementSql);
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schema_migrations(version, script_name, applied_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.resource());
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
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

    /**
     * Découpe les scripts du projet sur les points-virgules hors chaînes SQL.
     * Le migrateur reste volontairement minimal : les migrations NEXUS doivent
     * utiliser des instructions SQL simples et portables pour SQLite.
     */
    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;

        for (int index = 0; index < script.length(); index++) {
            char character = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';

            if (lineComment) {
                if (character == '\n') {
                    lineComment = false;
                    current.append(character);
                }
                continue;
            }

            if (!singleQuoted && !doubleQuoted && character == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }

            if (character == '\'' && !doubleQuoted) {
                if (singleQuoted && next == '\'') {
                    current.append(character).append(next);
                    index++;
                    continue;
                }
                singleQuoted = !singleQuoted;
            } else if (character == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            }

            if (character == ';' && !singleQuoted && !doubleQuoted) {
                statements.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }

        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    private record Migration(int version, String resource) {
    }
}
