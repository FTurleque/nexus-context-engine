package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ownership commun des readers Lucene persistants utilisés par les index lexical
 * et sémantique. Les writers restent hors de ce composant et conservent leur
 * lifecycle operation-scoped.
 */
public final class PersistentLuceneReaderSupport implements AutoCloseable {

    private final NexusPaths paths;
    private final BoundedLuceneSearcherCache searcherCache;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PersistentLuceneReaderSupport(NexusPaths paths, int cacheCapacity) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.searcherCache = new BoundedLuceneSearcherCache(cacheCapacity);
    }

    public <T> T search(
            UUID projectId,
            Path indexPath,
            BoundedLuceneSearcherCache.SearchOperation<T> operation,
            IoSupplier<T> fallback) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(indexPath, "indexPath");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(fallback, "fallback");
        ensureOpen();

        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return fallback.get();
        }
        paths.ensurePrivateDirectory(indexPath);

        BoundedLuceneSearcherCache.SearchLookup<T> lookup =
                searcherCache.search(projectId, indexPath, operation);
        return lookup.cached() ? lookup.value() : fallback.get();
    }

    public void refreshIfCached(UUID projectId) throws IOException {
        ensureOpen();
        searcherCache.refreshIfCached(projectId);
    }

    public void invalidate(UUID projectId) throws IOException {
        ensureOpen();
        searcherCache.invalidate(projectId);
    }

    public int cachedProjectCount() {
        return searcherCache.cachedProjectCount();
    }

    public void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Persistent Lucene reader support is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            searcherCache.close();
        }
    }

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }
}
