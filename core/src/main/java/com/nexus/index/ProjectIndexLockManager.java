package com.nexus.index;

import com.nexus.security.PrivateStoragePolicy;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * Verrou inter-processus des mutations et lectures d'index par projet.
 *
 * <p>Le fichier de lock reste présent après libération ; c'est le verrou OS porté
 * par {@link FileLock} qui représente la propriété exclusive ou partagée. Son contenu n'a
 * aucune sémantique et NEXUS ne le tronque ni ne l'utilise comme stockage.</p>
 *
 * <p>Les lectures acquièrent un verrou OS partagé, mutualisé entre lecteurs du
 * même processus, afin de ne jamais observer un dérivé Lucene pendant son
 * remplacement. La coordination intra-JVM utilise un sémaphore équivalent à un
 * read/write gate : une lecture consomme un permis, une mutation tous les permis.
 * La composition de production porte également le budget global non bloquant des
 * indexations coûteuses.</p>
 */
public final class ProjectIndexLockManager {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    private static final int PROCESS_READ_PERMITS = 1_000_000;
    private static final ConcurrentMap<String, Semaphore> PROCESS_GATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, SharedReadLock> SHARED_READ_LOCKS =
            new ConcurrentHashMap<>();

    private final NexusPaths paths;
    private final int maxConcurrentIndexing;

    private ProjectIndexLockManager(NexusPaths paths, int maxConcurrentIndexing) {
        this.paths = paths;
        this.maxConcurrentIndexing = maxConcurrentIndexing;
    }

    /** Active le verrouillage inter-processus dans le NEXUS_HOME fourni, avec le budget d'indexation par défaut. */
    public static ProjectIndexLockManager fileBacked(NexusPaths paths) {
        return fileBacked(paths, IndexingCapacityGate.DEFAULT_MAX_CONCURRENT_INDEXING);
    }

    /**
     * Composition de production : verrouillage inter-processus et budget global d'indexations
     * concurrentes résolu par la composition root ({@link #maxConcurrentIndexing(Map)}).
     */
    public static ProjectIndexLockManager fileBacked(NexusPaths paths, int maxConcurrentIndexing) {
        if (maxConcurrentIndexing <= 0
                || maxConcurrentIndexing > IndexingCapacityGate.MAX_CONFIGURED_CONCURRENT_INDEXING) {
            throw new IllegalArgumentException(
                    "maxConcurrentIndexing doit être compris entre 1 et "
                            + IndexingCapacityGate.MAX_CONFIGURED_CONCURRENT_INDEXING);
        }
        return new ProjectIndexLockManager(Objects.requireNonNull(paths, "paths"), maxConcurrentIndexing);
    }

    /** Résout {@code NEXUS_MAX_CONCURRENT_INDEXING} depuis l'environnement fourni par la composition root. */
    public static int maxConcurrentIndexing(Map<String, String> environment) {
        return IndexingCapacityGate.configuredLimit(environment);
    }

    /**
     * Verrouillage limité au processus, réservé aux tests unitaires qui n'ont pas de
     * {@code NEXUS_HOME}. Aucun code de production ne l'appelle : la composition de
     * production fournit explicitement {@link #fileBacked(NexusPaths)}.
     */
    public static ProjectIndexLockManager processLocalOnly() {
        return new ProjectIndexLockManager(null, IndexingCapacityGate.DEFAULT_MAX_CONCURRENT_INDEXING);
    }

    public LockHandle acquire(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        if (paths == null) {
            return LockHandle.noop();
        }

        IndexingCapacityGate.Permit capacityPermit = IndexingCapacityGate.acquireShared(maxConcurrentIndexing);
        boolean permitTransferred = false;
        Semaphore processGate = processGate(projectId);
        if (!processGate.tryAcquire(PROCESS_READ_PERMITS)) {
            capacityPermit.close();
            throw busy(projectId);
        }
        boolean processGateTransferred = false;
        try {
            Path locksDirectory = paths.locksDirectory();
            PrivateStoragePolicy.ensurePrivateDirectory(paths, locksDirectory);

            Path lockPath = paths.projectIndexLock(projectId);
            FileChannel channel = openHardenedChannel(lockPath);
            FileLock fileLock = acquireFileLock(channel, projectId, false);
            LockHandle handle = new LockHandle(
                    channel,
                    fileLock,
                    capacityPermit,
                    () -> processGate.release(PROCESS_READ_PERMITS));
            permitTransferred = true;
            processGateTransferred = true;
            return handle;
        } finally {
            if (!permitTransferred) {
                capacityPermit.close();
            }
            if (!processGateTransferred) {
                processGate.release(PROCESS_READ_PERMITS);
            }
        }
    }

    /** Acquiert une vue de lecture cohérente avec les mutations locales du projet. */
    public LockHandle acquireRead(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        if (paths == null) {
            return LockHandle.noop();
        }
        Semaphore processGate = processGate(projectId);
        processGate.acquireUninterruptibly();
        boolean processGateTransferred = false;
        try {
            Path locksDirectory = paths.locksDirectory();
            PrivateStoragePolicy.ensurePrivateDirectory(paths, locksDirectory);
            Path lockPath = paths.projectIndexLock(projectId);
            SharedReadLock sharedReadLock = acquireSharedReadLock(lockPath, projectId);
            LockHandle handle = new LockHandle(sharedReadLock, processGate::release);
            processGateTransferred = true;
            return handle;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Impossible d'acquérir le verrou de lecture du projet " + projectId,
                    failure);
        } finally {
            if (!processGateTransferred) {
                processGate.release();
            }
        }
    }

    private SharedReadLock acquireSharedReadLock(Path lockPath, UUID projectId) throws IOException {
        String key = lockKey(lockPath);
        synchronized (SHARED_READ_LOCKS) {
            SharedReadLock existing = SHARED_READ_LOCKS.get(key);
            if (existing != null) {
                existing.references++;
                return existing;
            }
            PrivateStoragePolicy.ensurePrivateFile(paths, lockPath);
            FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            FileLock fileLock;
            try {
                fileLock = acquireFileLock(channel, projectId, true);
            } catch (IOException | RuntimeException failure) {
                closeQuietly(channel);
                throw failure;
            }
            SharedReadLock created = new SharedReadLock(key, channel, fileLock);
            SHARED_READ_LOCKS.put(key, created);
            return created;
        }
    }

    private Semaphore processGate(UUID projectId) {
        Path lockPath = paths.projectIndexLock(projectId).toAbsolutePath().normalize();
        String key = lockKey(lockPath);
        return PROCESS_GATES.computeIfAbsent(key, ignored -> new Semaphore(PROCESS_READ_PERMITS, true));
    }

    private static String lockKey(Path lockPath) {
        String normalized = lockPath.toAbsolutePath().normalize().toString();
        return WINDOWS ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private FileChannel openHardenedChannel(Path lockPath) throws IOException {
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            PrivateStoragePolicy.hardenPrivateFile(paths, lockPath);
            return channel;
        } catch (IOException failure) {
            closeQuietly(channel);
            throw failure;
        }
    }

    private static FileLock acquireFileLock(FileChannel channel, UUID projectId, boolean shared) throws IOException {
        FileLock fileLock;
        try {
            fileLock = channel.tryLock(0L, Long.MAX_VALUE, shared);
        } catch (OverlappingFileLockException alreadyLockedInJvm) {
            closeQuietly(channel);
            throw busy(projectId);
        } catch (NonReadableChannelException unsupportedSharedLock) {
            closeQuietly(channel);
            throw new IOException("Le système de fichiers ne permet pas le verrou de lecture partagé", unsupportedSharedLock);
        } catch (IOException failure) {
            closeQuietly(channel);
            throw failure;
        }
        if (fileLock == null) {
            closeQuietly(channel);
            throw busy(projectId);
        }
        return fileLock;
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

    static void releaseAndClose(IoOperation releaseOperation, IoOperation closeOperation) throws IOException {
        IOException releaseFailure = null;
        try {
            releaseOperation.run();
        } catch (IOException failure) {
            releaseFailure = failure;
        }

        try {
            // La fermeture du channel libère également tous ses locks. Si elle réussit,
            // un échec préalable de FileLock.release() n'a plus d'impact matériel et ne
            // doit pas transformer une mutation déjà validée en faux échec métier.
            closeOperation.run();
            return;
        } catch (IOException closeFailure) {
            if (releaseFailure != null) {
                closeFailure.addSuppressed(releaseFailure);
            }
            throw closeFailure;
        }
    }

    @FunctionalInterface
    interface IoOperation {
        void run() throws IOException;
    }

    public static final class LockHandle implements AutoCloseable {

        private static final LockHandle NOOP = new LockHandle(null, null, null, null, null);

        private final FileChannel channel;
        private final FileLock fileLock;
        private final IndexingCapacityGate.Permit capacityPermit;
        private final Runnable processPermitRelease;
        private final SharedReadLock sharedReadLock;
        private boolean closed;

        private LockHandle(
                FileChannel channel,
                FileLock fileLock,
                IndexingCapacityGate.Permit capacityPermit,
                Runnable processPermitRelease) {
            this(channel, fileLock, capacityPermit, processPermitRelease, null);
        }

        private LockHandle(
                SharedReadLock sharedReadLock,
                Runnable processPermitRelease) {
            this(null, null, null, processPermitRelease, sharedReadLock);
        }

        private LockHandle(
                FileChannel channel,
                FileLock fileLock,
                IndexingCapacityGate.Permit capacityPermit,
                Runnable processPermitRelease,
                SharedReadLock sharedReadLock) {
            this.channel = channel;
            this.fileLock = fileLock;
            this.capacityPermit = capacityPermit;
            this.processPermitRelease = processPermitRelease;
            this.sharedReadLock = sharedReadLock;
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
            try {
                if (sharedReadLock != null) {
                    sharedReadLock.release();
                } else if (fileLock != null && channel != null) {
                    releaseAndClose(fileLock::release, channel::close);
                }
            } finally {
                try {
                    if (processPermitRelease != null) {
                        processPermitRelease.run();
                    }
                } finally {
                    if (capacityPermit != null) {
                        capacityPermit.close();
                    }
                }
            }
        }
    }

    private static final class SharedReadLock {
        private final String key;
        private final FileChannel channel;
        private final FileLock fileLock;
        private int references = 1;

        private SharedReadLock(String key, FileChannel channel, FileLock fileLock) {
            this.key = key;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        private void release() throws IOException {
            synchronized (SHARED_READ_LOCKS) {
                references--;
                if (references > 0) {
                    return;
                }
                try {
                    releaseAndClose(fileLock::release, channel::close);
                } finally {
                    SHARED_READ_LOCKS.remove(key, this);
                }
            }
        }
    }
}
