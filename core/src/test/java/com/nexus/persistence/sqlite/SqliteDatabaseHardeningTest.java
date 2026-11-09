package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.testsupport.SymbolicLinks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDatabaseHardeningTest {

    private static final Path DATABASE_SOURCE =
            Path.of("core/src/main/java/com/nexus/persistence/sqlite/SqliteDatabase.java");
    private static final Pattern CONNECTION_OPENING =
            Pattern.compile("\\bDriverManager\\s*\\.\\s*getConnection\\s*\\(|\"jdbc:sqlite:");
    private static final Pattern OTHER_TEMP_STORE =
            Pattern.compile("PRAGMA\\s+temp_store\\s*=\\s*(?!(?:MEMORY|2)\\b)['\"]?\\w+", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesBusyTimeoutAndForeignKeysThroughTypedConfiguration() throws Exception {
        int expectedBusyTimeout = 1_234;
        SqliteDatabase database = new SqliteDatabase(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")),
                expectedBusyTimeout,
                SqliteWriteRetryPolicy.defaults());

        try (Connection connection = database.openConnection()) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA busy_timeout")) {
                result.next();
                assertEquals(expectedBusyTimeout, result.getInt(1));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void sizesThePageCacheOfEveryConnection() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("cache-home")));

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA cache_size")) {
            result.next();
            assertEquals(-SqliteDatabase.PAGE_CACHE_KIB, result.getInt(1),
                    "Le cache de pages SQLite est fixé par docs/developer/sqlite-write-contention.md : remesurer avant de le changer");
        }
    }

    @Test
    void keepsTheRollbackJournalOnEveryConnection() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("journal-home")));

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            result.next();
            assertEquals("delete", result.getString(1).toLowerCase(java.util.Locale.ROOT),
                    "Le journal rollback (delete) est fixé par DATA-0088 : un autre mode, dont WAL, exige un ADR qui le remplace");
        }
    }

    @Test
    void keepsTemporaryStorageOfEveryConnectionInMemory() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("temp-home")));

        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA temp_store")) {
            result.next();
            assertEquals(2, result.getInt(1), "Les temporaires SQLite restent en mémoire (temp_store=MEMORY)");
        }
    }

    @Test
    void onlySqliteDatabaseOpensConnectionsAndNothingElseSetsTempStore() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> javaSources = new ArrayList<>();
        for (Path root : List.of(Path.of("core/src/main/java"), Path.of("adapters"))) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.toString().endsWith(".java"))
                        .filter(file -> file.toString().replace('\\', '/').contains("/src/main/java/"))
                        .forEach(javaSources::add);
            }
        }
        assertTrue(javaSources.contains(DATABASE_SOURCE), "SqliteDatabase.java introuvable : " + DATABASE_SOURCE);
        for (Path source : javaSources) {
            String code = COMMENT.matcher(Files.readString(source)).replaceAll(" ");
            if (!source.equals(DATABASE_SOURCE) && CONNECTION_OPENING.matcher(code).find()) {
                violations.add(source + " : connexion SQLite ouverte hors de SqliteDatabase");
            }
            Matcher other = OTHER_TEMP_STORE.matcher(code);
            while (other.find()) {
                violations.add(source + " : " + other.group());
            }
        }
        try (Stream<Path> migrations = Files.list(Path.of("core/src/main/resources/db/migration"))) {
            for (Path migration : migrations.filter(file -> file.toString().endsWith(".sql")).toList()) {
                Matcher other = OTHER_TEMP_STORE.matcher(Files.readString(migration));
                while (other.find()) {
                    violations.add(migration + " : " + other.group());
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void rejectsDatabaseSymlinkBeforeJdbcCanCreateItsExternalTarget() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("symlink-home"));
        Path externalTarget = temporaryDirectory.resolve("external.db");
        Path databaseLink = home.resolve("nexus.db");
        SymbolicLinks.createOrSkip(databaseLink, externalTarget.toAbsolutePath());

        assertThrows(IOException.class, () -> new SqliteDatabase(new NexusPaths(home)));

        assertFalse(Files.exists(externalTarget, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void suppressesCloseFailureOnPrimaryFailure() {
        SQLException primaryFailure = new SQLException("primary");
        SQLException closeFailure = new SQLException("close");
        Connection connection = connectionFailingOnClose(closeFailure);

        assertDoesNotThrow(() -> SqliteDatabase.closePreserving(connection, primaryFailure, false));

        assertEquals(1, primaryFailure.getSuppressed().length);
        assertSame(closeFailure, primaryFailure.getSuppressed()[0]);
    }

    @Test
    void neverEscapesCloseFailureAfterCommit() {
        Connection connection = connectionFailingOnClose(new SQLException("close"));

        assertDoesNotThrow(() -> SqliteDatabase.closePreserving(connection, null, true));
    }

    private static Connection connectionFailingOnClose(SQLException closeFailure) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if ("close".equals(method.getName())) {
                        throw closeFailure;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
