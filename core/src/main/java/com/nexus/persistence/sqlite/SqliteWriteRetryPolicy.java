package com.nexus.persistence.sqlite;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Politique centralisée de récupération des contentions transitoires SQLite.
 *
 * <p>Seuls les codes officiels {@code SQLITE_BUSY} et {@code SQLITE_LOCKED}
 * autorisent un nouvel essai. Le callback représente une tentative complète :
 * il doit donc ouvrir sa propre connexion et terminer rollback/close avant de
 * rendre l'exception à cette politique.</p>
 */
final class SqliteWriteRetryPolicy {

    static final int DEFAULT_MAX_ATTEMPTS = 2;
    static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(50);
    static final Duration DEFAULT_MAX_BACKOFF = Duration.ofMillis(50);

    private static final System.Logger LOGGER = System.getLogger(SqliteWriteRetryPolicy.class.getName());

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private final AtomicLong retryCount = new AtomicLong();

    static SqliteWriteRetryPolicy defaults() {
        return new SqliteWriteRetryPolicy(
                DEFAULT_MAX_ATTEMPTS,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF,
                Thread::sleep);
    }

    SqliteWriteRetryPolicy(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Sleeper sleeper) {
        if (maxAttempts < 1 || maxAttempts > 8) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 8");
        }
        this.initialBackoff = requireBackoff(initialBackoff, "initialBackoff");
        this.maxBackoff = requireBackoff(maxBackoff, "maxBackoff");
        if (this.maxBackoff.compareTo(this.initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be greater than or equal to initialBackoff");
        }
        this.maxAttempts = maxAttempts;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    <T> T execute(String operation, SqlOperation<T> operationAttempt) throws SQLException {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(operationAttempt, "operationAttempt");

        for (int attempt = 1; ; attempt++) {
            try {
                return operationAttempt.execute();
            } catch (SQLException failure) {
                if (!isTransientContention(failure) || attempt >= maxAttempts) {
                    throw failure;
                }
                Duration backoff = backoffForRetry(attempt);
                retryCount.incrementAndGet();
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Contention SQLite transitoire pendant {0}; nouvelle tentative de la transaction complète "
                                + "({1}/{2}) après {3} ms, code={4}",
                        operation,
                        attempt + 1,
                        maxAttempts,
                        backoff.toMillis(),
                        contentionCode(failure));
                sleep(backoff, operation, failure);
            }
        }
    }

    long retryCount() {
        return retryCount.get();
    }

    long worstCaseContentionMillis(int busyTimeoutMillis) {
        long total = Math.multiplyExact((long) busyTimeoutMillis, maxAttempts);
        for (int retry = 1; retry < maxAttempts; retry++) {
            total = Math.addExact(total, backoffForRetry(retry).toMillis());
        }
        return total;
    }

    static boolean isTransientContention(SQLException failure) {
        if (failure == null) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SQLiteException sqliteException
                    && isRetryableCode(sqliteException.getResultCode())) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                SQLException next = sqlException.getNextException();
                while (next != null && visited.add(next)) {
                    if (next instanceof SQLiteException sqliteNext
                            && isRetryableCode(sqliteNext.getResultCode())) {
                        return true;
                    }
                    next = next.getNextException();
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRetryableCode(SQLiteErrorCode code) {
        return code == SQLiteErrorCode.SQLITE_BUSY || code == SQLiteErrorCode.SQLITE_LOCKED;
    }

    private static String contentionCode(SQLException failure) {
        Throwable current = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && visited.add(current)) {
            if (current instanceof SQLiteException sqliteException
                    && isRetryableCode(sqliteException.getResultCode())) {
                return sqliteException.getResultCode().name();
            }
            current = current.getCause();
        }
        return "BUSY_OR_LOCKED";
    }

    private Duration backoffForRetry(int retryNumber) {
        long initialMillis = initialBackoff.toMillis();
        long maximumMillis = maxBackoff.toMillis();
        long multiplier = 1L << Math.clamp(retryNumber - 1, 0, 30);
        long candidate;
        try {
            candidate = Math.multiplyExact(initialMillis, multiplier);
        } catch (ArithmeticException overflow) {
            candidate = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(candidate, maximumMillis));
    }

    private void sleep(Duration backoff, String operation, SQLException contention) throws SQLException {
        try {
            sleeper.sleep(backoff.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SQLException failure = new SQLException(
                    "Interruption pendant le backoff SQLite de " + operation,
                    interrupted);
            failure.addSuppressed(contention);
            throw failure;
        }
    }

    private static Duration requireBackoff(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.compareTo(Duration.ofSeconds(1)) > 0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1 second");
        }
        return value;
    }

    @FunctionalInterface
    interface SqlOperation<T> {
        T execute() throws SQLException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
