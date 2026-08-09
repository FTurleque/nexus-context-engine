package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.persistence.PersistenceException;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline NXA-09 : reproduit la contention réelle de deux writers sur la base
 * SQLite partagée avant l'introduction de la politique de récupération.
 */
class SqliteCrossProjectWriteContentionBaselineTest {

    @TempDir
    Path tempDir;

    @Test
    void currentBusyTimeoutStillLetsCrossProjectWriterFailWithOfficialBusyCode() throws Exception {
        NexusPaths paths = new NexusPaths(tempDir.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);

        ProjectDescriptor projectA = project("project-a");
        ProjectDescriptor projectB = project("project-b");
        projects.save(projectA);
        projects.save(projectB);

        try (Connection writerA = database.openConnection()) {
            writerA.setAutoCommit(false);
            try (PreparedStatement statement = writerA.prepareStatement(
                    "UPDATE projects SET name = ? WHERE id = ?")) {
                statement.setString(1, "project-a-holds-write-lock");
                statement.setString(2, projectA.id().toString());
                statement.executeUpdate();
            }

            long startedAt = System.nanoTime();
            PersistenceException failure = assertThrows(
                    PersistenceException.class,
                    () -> projects.save(new ProjectDescriptor(
                            projectB.id(),
                            "project-b-contending-write",
                            projectB.rootPath(),
                            projectB.sourceType(),
                            projectB.languages(),
                            projectB.technologies(),
                            projectB.lastIndexedAt(),
                            projectB.indexStatus())));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            SQLiteException sqliteFailure = sqliteFailure(failure);
            assertNotNull(sqliteFailure, "La cause SQLite officielle doit rester accessible");
            assertTrue(
                    sqliteFailure.getResultCode() == SQLiteErrorCode.SQLITE_BUSY
                            || sqliteFailure.getResultCode() == SQLiteErrorCode.SQLITE_LOCKED,
                    () -> "Code SQLite inattendu : " + sqliteFailure.getResultCode());
            assertTrue(
                    elapsedMillis >= 4_000L,
                    () -> "Le writer concurrent a échoué avant le busy_timeout attendu : " + elapsedMillis + " ms");
            assertTrue(
                    elapsedMillis < 8_000L,
                    () -> "La baseline doit rester bornée autour du busy_timeout de 5 s : " + elapsedMillis + " ms");

            System.out.println(
                    "NXA-09 baseline cross-project contention: result="
                            + sqliteFailure.getResultCode()
                            + ", elapsedMs=" + elapsedMillis);
            writerA.rollback();
        }
    }

    private ProjectDescriptor project(String name) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                tempDir.resolve(name).toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }

    private static SQLiteException sqliteFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLiteException sqliteException) {
                return sqliteException;
            }
            current = current.getCause();
        }
        return null;
    }
}
