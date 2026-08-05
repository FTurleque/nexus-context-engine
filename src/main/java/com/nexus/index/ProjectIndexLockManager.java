package com.nexus.index;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Verrou inter-processus des mutations d'index par projet.
 *
 * <p>Le fichier de lock reste présent après libération ; c'est le verrou OS porté
 * par {@link FileLock} qui représente la propriété exclusive. Son contenu n'a
 * aucune sémantique et NEXUS ne le tronque ni ne l'utilise comme stockage.</p>
 */
public final class ProjectIndexLockManager {

    private final NexusPaths paths;

    private ProjectIndexLockManager(NexusPaths paths) {
        this.paths = paths;
    }

    /** Active le verrouillage inter-processus dans le NEXUS_HOME fourni. */
    public static ProjectIndexLockManager fileBacked(NexusPaths paths) {
        return new ProjectIndexLockManager(Objects.requireNonNull(paths, "paths"));
    }

    /**
     * Mode de compatibilité pour les constructions unitaires historiques du
     * service. La façade de production utilise toujours {@link #fileBacked(NexusPaths)}.
     */
    public static ProjectIndexLockManager processLocalOnly() {
        return new ProjectIndexLockManager(null);
    }

    public LockHandle acquire(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        if (paths == null) {
            return LockHandle.noop();
        }

        Path locksDirectory = paths.locksDirectory();
        Files.createDirectories(locksDirectory);
        if (Files.isSymbolicLink(locksDirectory)
                || !Files.isDirectory(locksDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Le répertoire de locks NEXUS est invalide ou symbolique : " + locksDirectory);
        }

        Path lockPath = paths.projectIndexLock(projectId);
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileLock fileLock;
        try {
            fileLock = channel.tryLock();
        } catch (OverlappingFileLockException alreadyLockedInJvm) {
            closeQuietly(channel);
            throw busy(projectId);
        } catch (IOException failure) {
            closeQuietly(channel);
            throw failure;
        }
        if (fileLock == null) {
            closeQuietly(channel);
            throw busy(projectId);
        }

        return new LockHandle(channel, fileLock);
    }

    private static IllegalStateException busy(UUID projectId) {
        return new IllegalStateException(
                "Une mutation d'index est déjà en cours pour le projet " + projectId);
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort lors d'un échec d'acquisition.
        }
    }

    public static final class LockHandle implements AutoCloseable {

        private static final LockHandle NOOP = new LockHandle(null, null);

        private final FileChannel channel;
        private final FileLock fileLock;
        private boolean closed;

        private LockHandle(FileChannel channel, FileLock fileLock) {
            this.channel = channel;
            this.fileLock = fileLock;
        }

        private static LockHandle noop() {
            return NOOP;
        }

        @Override
        public void close() throws IOException {
            if (this == NOOP || closed) {
                return;
            }
            closed = true;
            IOException releaseFailure = null;
            try {
                fileLock.release();
            } catch (IOException failure) {
                releaseFailure = failure;
            }
            try {
                // Fermer le channel libère également les locks associés. Si la
                // fermeture réussit, une erreur préalable de release ne doit pas
                // transformer une mutation déjà validée en faux échec métier.
                channel.close();
            } catch (IOException closeFailure) {
                if (releaseFailure != null) {
                    closeFailure.addSuppressed(releaseFailure);
                }
                throw closeFailure;
            }
        }
    }
}
