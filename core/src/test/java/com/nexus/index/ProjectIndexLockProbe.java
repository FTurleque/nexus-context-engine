package com.nexus.index;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Processus auxiliaire utilisé par la qualification filesystem pour vérifier
 * la sémantique réelle du FileLock entre deux JVM distinctes.
 */
public final class ProjectIndexLockProbe {

    static final int EXIT_ACQUIRED = 0;
    static final int EXIT_BUSY = 75;
    static final int EXIT_ERROR = 1;
    static final String MODE_READ = "read";
    static final String MODE_WRITE = "write";

    private ProjectIndexLockProbe() {
    }

    public static void main(String[] args) {
        try {
            String mode = validateAndResolveMode(args);
            NexusPaths paths = new NexusPaths(Path.of(args[0]));
            UUID projectId = UUID.fromString(args[1]);
            ProjectIndexLockManager manager = ProjectIndexLockManager.fileBacked(paths);
            try (ProjectIndexLockManager.LockHandle ignored = acquire(manager, projectId, mode)) {
                System.exit(EXIT_ACQUIRED);
            }
        } catch (IllegalStateException busy) {
            System.err.println(busy.getMessage());
            System.exit(EXIT_BUSY);
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            System.exit(EXIT_ERROR);
        }
    }

    private static String validateAndResolveMode(String[] args) {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "usage: ProjectIndexLockProbe <nexus-home> <project-uuid> [read|write]");
        }
        String mode = args.length == 3 ? args[2] : MODE_WRITE;
        if (!MODE_READ.equals(mode) && !MODE_WRITE.equals(mode)) {
            throw new IllegalArgumentException(
                    "usage: ProjectIndexLockProbe <nexus-home> <project-uuid> [read|write]");
        }
        return mode;
    }

    private static ProjectIndexLockManager.LockHandle acquire(
            ProjectIndexLockManager manager,
            UUID projectId,
            String mode) throws IOException {
        if (MODE_READ.equals(mode)) {
            return manager.acquireRead(projectId);
        }
        return manager.acquire(projectId);
    }
}
