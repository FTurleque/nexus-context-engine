package com.nexus.index;

import com.nexus.config.NexusPaths;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Processus auxiliaire utilisé par la qualification filesystem pour vérifier
 * la sémantique réelle du FileLock entre deux JVM distinctes.
 */
public final class ProjectIndexLockProbe {

    static final int EXIT_ACQUIRED = 0;
    static final int EXIT_BUSY = 75;
    static final int EXIT_USAGE = 64;
    static final int EXIT_ERROR = 1;

    private ProjectIndexLockProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: ProjectIndexLockProbe <nexus-home> <project-uuid>");
            System.exit(EXIT_USAGE);
        }

        try {
            NexusPaths paths = new NexusPaths(Path.of(args[0]));
            UUID projectId = UUID.fromString(args[1]);
            try (ProjectIndexLockManager.LockHandle ignored =
                         ProjectIndexLockManager.fileBacked(paths).acquire(projectId)) {
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
}
