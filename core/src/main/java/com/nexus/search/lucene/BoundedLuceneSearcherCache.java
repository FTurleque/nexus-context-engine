package com.nexus.search.lucene;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cache borné de readers/searchers Lucene persistants.
 *
 * <p>Le cache ne possède aucun {@code IndexWriter}. Il peut donc rester ouvert
 * dans les processus longue durée sans conserver le write-lock Lucene. Quand la
 * capacité est atteinte, l'appelant reçoit un miss et doit utiliser son chemin
 * operation-scoped historique.</p>
 */
public final class BoundedLuceneSearcherCache implements AutoCloseable {

    private static final String PROJECT_ID_ARGUMENT = "projectId";

    private final int capacity;
    private final Map<UUID, ProjectSearcher> searchers = new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    private volatile boolean closed;

    public BoundedLuceneSearcherCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be greater than zero");
        }
        this.capacity = capacity;
    }

    /**
     * Exécute une recherche sur un searcher persistant quand le projet peut être
     * gardé chaud. Un miss signifie que l'appelant doit utiliser son fallback
     * operation-scoped ; il ne signifie pas que l'index est invalide.
     */
    public <T> SearchLookup<T> search(
            UUID projectId,
            Path indexPath,
            SearchOperation<T> operation) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        Objects.requireNonNull(indexPath, "indexPath");
        Objects.requireNonNull(operation, "operation");
        ensureOpen();

        ProjectSearcher searcher = searchers.get(projectId);
        if (searcher == null) {
            searcher = createIfCapacityAllows(projectId, indexPath);
            if (searcher == null) {
                return SearchLookup.miss();
            }
        }
        return SearchLookup.hit(searcher.search(operation));
    }

    /** Rafraîchit un reader déjà chaud après un commit local. */
    public void refreshIfCached(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        ensureOpen();
        ProjectSearcher searcher = searchers.get(projectId);
        if (searcher != null) {
            searcher.refresh();
        }
    }

    /**
     * Retire et ferme le reader d'un projet. La fermeture attend les recherches
     * locales déjà entrées dans leur section de lecture.
     */
    public void invalidate(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        ProjectSearcher removed;
        synchronized (lifecycleMonitor) {
            removed = searchers.remove(projectId);
        }
        if (removed != null) {
            removed.close();
        }
    }

    /** Visible pour les tests de borne de ressources. */
    public int cachedProjectCount() {
        return searchers.size();
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public void close() throws IOException {
        List<ProjectSearcher> toClose;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(searchers.values());
            searchers.clear();
        }

        IOException failure = null;
        for (ProjectSearcher searcher : toClose) {
            try {
                searcher.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private ProjectSearcher createIfCapacityAllows(UUID projectId, Path indexPath) throws IOException {
        synchronized (lifecycleMonitor) {
            ensureOpen();
            ProjectSearcher existing = searchers.get(projectId);
            if (existing != null) {
                return existing;
            }
            if (searchers.size() >= capacity) {
                return null;
            }

            ProjectSearcher created = ProjectSearcher.open(indexPath);
            if (created == null) {
                return null;
            }
            searchers.put(projectId, created);
            return created;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Lucene searcher cache is closed");
        }
    }

    @FunctionalInterface
    public interface SearchOperation<T> {
        T search(IndexSearcher searcher) throws IOException;
    }

    public record SearchLookup<T>(boolean cached, T value) {
        private static <T> SearchLookup<T> hit(T value) {
            return new SearchLookup<>(true, value);
        }

        private static <T> SearchLookup<T> miss() {
            return new SearchLookup<>(false, null);
        }
    }

    private static final class ProjectSearcher implements AutoCloseable {
        private final Directory directory;
        private final SearcherManager manager;
        private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
        private boolean closed;

        private ProjectSearcher(Directory directory, SearcherManager manager) {
            this.directory = directory;
            this.manager = manager;
        }

        private static ProjectSearcher open(Path indexPath) throws IOException {
            Directory directory = FSDirectory.open(indexPath);
            try {
                if (!DirectoryReader.indexExists(directory)) {
                    directory.close();
                    return null;
                }
                return new ProjectSearcher(directory, new SearcherManager(directory, null));
            } catch (IOException | RuntimeException failure) {
                try {
                    directory.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }

        private <T> T search(SearchOperation<T> operation) throws IOException {
            ReentrantReadWriteLock.ReadLock readLock = lifecycleLock.readLock();
            readLock.lock();
            try {
                ensureOpen();
                // NEXUS autorise plusieurs processus sur le même NEXUS_HOME. Le
                // refresh avant lecture rend visibles les commits effectués par
                // un autre processus sans conserver son writer localement.
                manager.maybeRefreshBlocking();
                IndexSearcher searcher = manager.acquire();
                try {
                    return operation.search(searcher);
                } finally {
                    manager.release(searcher);
                }
            } finally {
                readLock.unlock();
            }
        }

        private void refresh() throws IOException {
            ReentrantReadWriteLock.ReadLock readLock = lifecycleLock.readLock();
            readLock.lock();
            try {
                ensureOpen();
                manager.maybeRefreshBlocking();
            } finally {
                readLock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            ReentrantReadWriteLock.WriteLock writeLock = lifecycleLock.writeLock();
            writeLock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                IOException failure = null;
                try {
                    manager.close();
                } catch (IOException exception) {
                    failure = exception;
                }
                try {
                    directory.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            } finally {
                writeLock.unlock();
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Lucene project searcher is closed");
            }
        }
    }
}
