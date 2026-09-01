package com.nexus.index;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
 *
 * <p>La composition de production porte également le budget global non bloquant
 * des indexations coûteuses. La capacité est acquise avant le verrou fichier et
 * libérée avec le même handle, y compris lorsqu'une acquisition échoue.</p>
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

        IndexingCapacityGate.Permit capacityPermit = IndexingCapacityGate.acquireShared();
        try {
            Path locksDirectory = paths.locksDirectory();
            paths.ensurePrivateDirectory(locksDirectory);

            Path lockPath = paths.projectIndexLock(projectId);
            FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            try {
                paths.hardenPrivateFile(lockPath);
            } catch (IOException failure) {
                closeQuietly(channel);
                throw failure;
            }

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

            return new LockHandle(channel, fileLock, capacityPermit);
        } catch (IOException | RuntimeException | Error failure) {
            capacityPermit.close();
            throw failure;
        }
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

        private static final LockHandle NOOP = new LockHandle(null, null, null);

        private final FileChannel channel;
        private final FileLock fileLock;
        private final IndexingCapacityGate.Permit capacityPermit;
        private boolean closed;

        private LockHandle(
                FileChannel channel,
                FileLock fileLock,
                IndexingCapacityGate.Permit capacityPermit) {
            this.channel = channel;
            this.fileLock = fileLock;
            this.capacityPermit = capacityPermit;
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
                releaseFailure = closeFailure;
            } finally {
                capacityPermit.close();
            }
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }
}
