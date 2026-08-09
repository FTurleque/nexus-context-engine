package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SqliteDatabase {

    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_BUSY_TIMEOUT_MILLIS = 60_000;

    private final Path databaseFile;
    private final int busyTimeoutMillis;
    private final SqliteWriteRetryPolicy writeRetryPolicy;

    public SqliteDatabase(NexusPaths paths) throws SQLException, IOException {
        this(paths, DEFAULT_BUSY_TIMEOUT_MILLIS, SqliteWriteRetryPolicy.defaults());
    }

    SqliteDatabase(
            NexusPaths paths,
            int busyTimeoutMillis,
            SqliteWriteRetryPolicy writeRetryPolicy) throws SQLException, IOException {
        Objects.requireNonNull(paths, "paths");
        if (busyTimeoutMillis < 0 || busyTimeoutMillis > MAX_BUSY_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "busyTimeoutMillis must be between 0 and " + MAX_BUSY_TIMEOUT_MILLIS);
        }
        this.databaseFile = paths.databaseFile();
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.writeRetryPolicy = Objects.requireNonNull(writeRetryPolicy, "writeRetryPolicy");
        Path parent = databaseFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        migrateWithRetry();
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + busyTimeoutMillis);
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    <T> T writeTransaction(String operation, SqlTransaction<T> transaction) throws SQLException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(transaction, "transaction");
        return writeRetryPolicy.execute(operation, () -> executeTransactionAttempt(transaction));
    }

    void writeTransaction(String operation, SqlRunnable transaction) throws SQLException {
        writeTransaction(operation, connection -> {
            transaction.execute(connection);
            return null;
        });
    }

    int busyTimeoutMillis() {
        return busyTimeoutMillis;
    }

    long writeRetryCount() {
        return writeRetryPolicy.retryCount();
    }

    long worstCaseContentionMillis() {
        return writeRetryPolicy.worstCaseContentionMillis(busyTimeoutMillis);
    }

    public Path databaseFile() {
        return databaseFile;
    }

    private <T> T executeTransactionAttempt(SqlTransaction<T> transaction) throws SQLException {
        Connection connection = openConnection();
        boolean committed = false;
        try {
            connection.setAutoCommit(false);
            T result = transaction.execute(connection);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException failure) {
            if (!committed) {
                rollbackPreserving(connection, failure);
            }
            throw failure;
        } finally {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                // Une transaction déjà commitée ne doit jamais être rejouée à cause
                // d'un échec de fermeture de connexion : cela violerait l'exactly-once.
                if (!committed) {
                    throw closeFailure;
                }
            }
        }
    }

    private void migrateWithRetry() throws SQLException, IOException {
        try {
            writeRetryPolicy.execute("bootstrap/migrations SQLite", () -> {
                try (Connection connection = openConnection()) {
                    try {
                        SchemaMigrator.migrate(connection);
                        return null;
                    } catch (IOException ioFailure) {
                        throw new MigrationIOException(ioFailure);
                    }
                }
            });
        } catch (MigrationIOException wrapped) {
            throw wrapped.ioFailure;
        }
    }

    private static void rollbackPreserving(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    interface SqlTransaction<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface SqlRunnable {
        void execute(Connection connection) throws SQLException;
    }

    private static final class MigrationIOException extends RuntimeException {

        private final IOException ioFailure;

        private MigrationIOException(IOException ioFailure) {
            super(ioFailure);
            this.ioFailure = ioFailure;
        }
    }
}
