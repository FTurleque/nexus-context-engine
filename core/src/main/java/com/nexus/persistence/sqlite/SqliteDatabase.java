package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SqliteDatabase {

    public static final int DEFAULT_BUSY_TIMEOUT_MILLIS = 5_000;
    private static final int MAX_BUSY_TIMEOUT_MILLIS = 60_000;
    private static final System.Logger LOGGER = System.getLogger(SqliteDatabase.class.getName());

    private final NexusPaths paths;
    private final Path databaseFile;
    private final int busyTimeoutMillis;
    private final SqliteWriteRetryPolicy writeRetryPolicy;
    private final ThreadLocal<ReadConnections> readConnections = new ThreadLocal<>();

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
        paths.ensurePrivateFile(databaseFile);
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.writeRetryPolicy = Objects.requireNonNull(writeRetryPolicy, "writeRetryPolicy");
        migrateWithRetry();
        paths.hardenPrivateFile(databaseFile);
    }

    public Connection openConnection() throws SQLException {
        ReadConnections reads = readConnections.get();
        if (reads == null) return newConnection();
        if (reads.connection == null) {
            reads.connection = newConnection();
            reads.borrowed = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (Thread.currentThread() != reads.owner) throw new SQLException("Session SQLite utilisée hors thread propriétaire");
                        if (method.getName().equals("close")) return null;
                        try { return method.invoke(reads.connection, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
        return reads.borrowed;
    }

    /** Réutilise le schéma chargé pendant la requête, sans transaction ni cache de résultats. */
    com.nexus.index.IndexRepository.ReadSession openReadSession() {
        ReadConnections current = readConnections.get();
        if (current == null) {
            current = new ReadConnections();
            readConnections.set(current);
        }
        ReadConnections reads = current;
        reads.depth++;
        return new com.nexus.index.IndexRepository.ReadSession() {
            private boolean closed;
            @Override public void close() {
                if (Thread.currentThread() != reads.owner) throw new IllegalStateException("Session SQLite fermée hors thread propriétaire");
                if (closed) return;
                closed = true;
                if (--reads.depth == 0) {
                    readConnections.remove();
                    if (reads.connection != null) {
                        try { reads.connection.close(); }
                        catch (SQLException failure) { throw new com.nexus.persistence.PersistenceException("Fermeture session SQLite impossible", failure); }
                    }
                }
            }
        };
    }

    private static final class ReadConnections {
        private final Thread owner = Thread.currentThread();
        private int depth;
        private Connection connection;
        private Connection borrowed;
    }

    private Connection newConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                // Trigger-heavy indexing otherwise creates temporary disk I/O for
                // each canonical row. Keep transient SQL work in memory; the main
                // database journal and synchronous durability remain unchanged.
                statement.execute("PRAGMA temp_store = MEMORY");
            }
            if (!(connection instanceof SQLiteConnection sqliteConnection)) {
                throw new SQLException("Unexpected JDBC connection type for SQLite database");
            }
            sqliteConnection.setBusyTimeout(busyTimeoutMillis);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
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
        Connection connection = newConnection();
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
