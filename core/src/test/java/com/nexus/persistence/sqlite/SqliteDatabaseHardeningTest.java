package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SqliteDatabaseHardeningTest {

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
