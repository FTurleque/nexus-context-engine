package com.nexus.index;

import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
}
