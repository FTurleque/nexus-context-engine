package com.nexus.persistence.sqlite;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaMigratorTest {

    @Test
    void preservesPrimaryFailureWhenRollbackAndAutoCommitRestoreAlsoFail() {
        SQLException primaryFailure = new SQLException("primary migration failure");
        SQLException rollbackFailure = new SQLException("rollback failure");
        SQLException restoreFailure = new SQLException("auto-commit restore failure");
        Connection connection = failingMigrationConnection(primaryFailure, rollbackFailure, restoreFailure);

        SQLException thrown = assertThrows(SQLException.class, () -> SchemaMigrator.migrate(connection));

        assertSame(primaryFailure, thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(rollbackFailure, thrown.getSuppressed()[0]);
        assertSame(restoreFailure, thrown.getSuppressed()[1]);
    }

    private static Connection failingMigrationConnection(
            SQLException primaryFailure,
            SQLException rollbackFailure,
            SQLException restoreFailure) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> {
                        boolean enabled = (Boolean) arguments[0];
                        if (enabled) {
                            throw restoreFailure;
                        }
                        yield null;
                    }
                    case "createStatement" -> throw primaryFailure;
                    case "rollback" -> throw rollbackFailure;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
