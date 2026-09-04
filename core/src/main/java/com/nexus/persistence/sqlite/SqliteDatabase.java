package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class SqliteDatabase {

    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_BUSY_TIMEOUT_MILLIS = 60_000;
    private static final System.Logger LOGGER = System.getLogger(SqliteDatabase.class.getName());

    private final NexusPaths paths;
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
        this.paths = Objects.requireNonNull(paths, "paths");
        if (busyTimeoutMillis < 0 || busyTimeoutMillis > MAX_BUSY_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "busyTimeoutMillis must be between 0 and " + MAX_BUSY_TIMEOUT_MILLIS);
        }
        paths.ensurePrivateStorage();
        this.databaseFile = paths.databaseFile();
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.writeRetryPolicy = Objects.requireNonNull(writeRetryPolicy, "writeRetryPolicy");
        migrateWithRetry();
        paths.hardenPrivateFile(databaseFile);
    }

    public Connection openConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(busyTimeoutMillis);
        return config.createConnection("jdbc:sqlite:" + databaseFile);
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
        Throwable pendingFailure = null;
        try {
            connection.setAutoCommit(false);
            T result = transaction.execute(connection);
            connection.commit();
            committed = true;
            return result;
        } catch (SQLException | RuntimeException failure) {
            pendingFailure = failure;
            rollbackPreserving(connection, failure);
            throw failure;
        } finally {
            closePreserving(connection, pendingFailure, committed);
        }
    }

    static void closePreserving(
            Connection connection,
            Throwable pendingFailure,
            boolean committed) {
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(closeFailure);
                return;
            }
            if (committed) {
                // Une transaction déjà commitée ne doit jamais être rejouée à
                // cause d'un échec de fermeture : cela violerait l'exactly-once.
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Connexion SQLite non fermée proprement après commit; transaction non rejouée",
                        closeFailure);
                return;
            }
            // Un Error ou autre échec non capturé est déjà en train de remonter.
            // Ne jamais le masquer par une erreur secondaire de fermeture.
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "Connexion SQLite non fermée proprement; échec principal préservé",
                    closeFailure);
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
