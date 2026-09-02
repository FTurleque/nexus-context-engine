package com.nexus.index;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexLockManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preventsASecondOwnerAndCanBeReacquiredAfterRelease() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        ProjectIndexLockManager firstManager = ProjectIndexLockManager.fileBacked(paths);
        ProjectIndexLockManager secondManager = ProjectIndexLockManager.fileBacked(paths);
        UUID projectId = UUID.randomUUID();

        try (ProjectIndexLockManager.LockHandle ignored = firstManager.acquire(projectId)) {
            assertThrows(IllegalStateException.class, () -> secondManager.acquire(projectId));
        }

        try (ProjectIndexLockManager.LockHandle ignored = secondManager.acquire(projectId)) {
            assertTrue(Files.isRegularFile(paths.projectIndexLock(projectId)));
        }
    }

    @Test
    void ignoresReleaseFailureWhenClosingTheChannelSucceeds() {
        assertDoesNotThrow(() -> ProjectIndexLockManager.releaseAndClose(
                () -> {
                    throw new IOException("release failed");
                },
                () -> {
                    // Channel close succeeded: the OS resource is released.
                }));
    }

    @Test
    void reportsCloseFailureAndRetainsReleaseFailureAsSuppressed() {
        IOException failure = assertThrows(IOException.class, () -> ProjectIndexLockManager.releaseAndClose(
                () -> {
                    throw new IOException("release failed");
                },
                () -> {
                    throw new IOException("close failed");
                }));

        assertEquals("close failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("release failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void refusesASymbolicLockDirectory() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("symlink-home"));
        Files.createDirectories(paths.home());
        Path external = Files.createDirectories(temporaryDirectory.resolve("external-locks"));

        Assumptions.assumeTrue(createSymbolicLink(paths.locksDirectory(), external),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        assertThrows(
                IOException.class,
                () -> ProjectIndexLockManager.fileBacked(paths).acquire(UUID.randomUUID()));
    }

    @Test
    void refusesASymbolicLockFileWithoutModifyingItsTarget() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("lock-file-home"));
        Files.createDirectories(paths.locksDirectory());
        Path victim = temporaryDirectory.resolve("victim.txt");
        Files.writeString(victim, "do-not-touch");
        UUID projectId = UUID.randomUUID();
        Path lockPath = paths.projectIndexLock(projectId);

        Assumptions.assumeTrue(createSymbolicLink(lockPath, victim),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        assertThrows(
                IOException.class,
                () -> ProjectIndexLockManager.fileBacked(paths).acquire(projectId));
        assertEquals("do-not-touch", Files.readString(victim));
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return false;
        }
    }
}
