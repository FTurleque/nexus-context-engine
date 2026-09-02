package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteWriteRetryPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void busyFailureRollsBackBeforeWholeTransactionRetryAndCommitsExactlyOnce() throws Exception {
        SqliteWriteRetryPolicy policy = new SqliteWriteRetryPolicy(
                2,
                Duration.ZERO,
                Duration.ZERO,
                ignored -> { });
        SqliteDatabase database = new SqliteDatabase(
                new NexusPaths(tempDir.resolve("rollback-retry")),
                0,
                policy);
        AtomicInteger attempts = new AtomicInteger();
        UUID projectId = UUID.randomUUID();

        database.writeTransaction("synthetic rollback probe", connection -> {
            int attempt = attempts.incrementAndGet();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                    VALUES (?, ?, ?, ?, NULL, ?)
                    """)) {
                statement.setString(1, projectId.toString());
                statement.setString(2, "retry-probe");
                statement.setString(3, tempDir.resolve("retry-probe").toAbsolutePath().normalize().toString());
                statement.setString(4, "LOCAL");
                statement.setString(5, "NOT_INDEXED");
                statement.executeUpdate();
            }
            if (attempt == 1) {
                throw new SQLiteException("synthetic transient contention", SQLiteErrorCode.SQLITE_BUSY);
            }
        });

        assertEquals(2, attempts.get(), "La transaction complète doit être rejouée une seule fois");
        assertEquals(1L, policy.retryCount());
        try (var connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM projects WHERE id = ?")) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(1L, resultSet.getLong(1),
                        "Le premier essai doit avoir été rollbacké avant le retry");
            }
        }
    }

    @Test
    void lockedIsRetryableButConstraintIsNot() throws Exception {
        AtomicInteger lockedAttempts = new AtomicInteger();
        SqliteWriteRetryPolicy lockedPolicy = new SqliteWriteRetryPolicy(
                2,
                Duration.ZERO,
                Duration.ZERO,
                ignored -> { });

        String result = lockedPolicy.execute("locked probe", () -> {
            if (lockedAttempts.incrementAndGet() == 1) {
                throw new SQLiteException("synthetic locked", SQLiteErrorCode.SQLITE_LOCKED);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, lockedAttempts.get());
        assertEquals(1L, lockedPolicy.retryCount());

        AtomicInteger constraintAttempts = new AtomicInteger();
        SqliteWriteRetryPolicy constraintPolicy = new SqliteWriteRetryPolicy(
                4,
                Duration.ZERO,
                Duration.ZERO,
                ignored -> { });
        SQLiteException constraint = assertThrows(SQLiteException.class, () ->
                constraintPolicy.execute("constraint probe", () -> {
                    constraintAttempts.incrementAndGet();
                    throw new SQLiteException("synthetic constraint", SQLiteErrorCode.SQLITE_CONSTRAINT);
                }));

        assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT, constraint.getResultCode());
        assertEquals(1, constraintAttempts.get(), "Une erreur non-BUSY/LOCKED ne doit jamais être rejouée");
        assertEquals(0L, constraintPolicy.retryCount());
    }

    @Test
    void retryBudgetAndBackoffAreStrictlyBounded() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong sleptMillis = new AtomicLong();
        SqliteWriteRetryPolicy policy = new SqliteWriteRetryPolicy(
                3,
                Duration.ofMillis(5),
                Duration.ofMillis(10),
                sleptMillis::addAndGet);

        SQLiteException failure = assertThrows(SQLiteException.class, () ->
                policy.execute("bounded probe", () -> {
                    attempts.incrementAndGet();
                    throw new SQLiteException("synthetic busy", SQLiteErrorCode.SQLITE_BUSY);
                }));

        assertEquals(SQLiteErrorCode.SQLITE_BUSY, failure.getResultCode());
        assertEquals(3, attempts.get());
        assertEquals(2L, policy.retryCount());
        assertEquals(15L, sleptMillis.get(), "Backoff attendu: 5 ms puis 10 ms");
        assertEquals(75L, policy.worstCaseContentionMillis(20),
                "3 busy_timeout de 20 ms + 5 ms + 10 ms de backoff");
    }
}
