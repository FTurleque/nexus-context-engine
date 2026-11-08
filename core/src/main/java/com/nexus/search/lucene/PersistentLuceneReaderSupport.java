package com.nexus.search.lucene;

import com.nexus.security.PrivateStoragePolicy;

import com.nexus.config.NexusPaths;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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
        PrivateStoragePolicy.ensurePrivateDirectory(paths, indexPath);

        BoundedLuceneSearcherCache.SearchLookup<T> lookup =
                searcherCache.search(projectId, indexPath, operation);
        return lookup.cached() ? lookup.value() : fallback.get();
    }

    /**
     * Lit les métadonnées de commit d'un index sans ouvrir de {@code DirectoryReader}
     * hors du cache (DATA-0074) : sur le reader déjà en cache s'il existe, sinon sur le
     * dernier commit ({@link SegmentInfos#readLatestCommit}), qui ne charge aucun
     * segment. Un répertoire absent ou sans index rend {@code whenAbsent}, sans erreur.
     */
    public <T> T readCommitMetadata(
            UUID projectId,
            Path indexPath,
            Function<Map<String, String>, T> reader,
            T whenAbsent) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(indexPath, "indexPath");
        Objects.requireNonNull(reader, "reader");
        ensureOpen();

        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return whenAbsent;
        }
        PrivateStoragePolicy.ensurePrivateDirectory(paths, indexPath);
        Optional<T> cached = searcherCache.searchIfCached(
                projectId, indexPath, searcher -> reader.apply(commitUserData(searcher)));
        if (cached.isPresent()) {
            return cached.get();
        }
        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return whenAbsent;
            }
            return reader.apply(SegmentInfos.readLatestCommit(directory).getUserData());
        }
    }

    private static Map<String, String> commitUserData(IndexSearcher searcher) throws IOException {
        if (searcher.getIndexReader() instanceof DirectoryReader directoryReader) {
            return directoryReader.getIndexCommit().getUserData();
        }
        throw new IllegalStateException("Le reader Lucene en cache n'est pas un DirectoryReader");
    }

    /** Nombre de {@code DirectoryReader} ouverts par le cache ; un hit n'en ouvre aucun. */
    public long openedReaderCount() {
        return searcherCache.openedReaderCount();
    }

    public void refreshIfCached(UUID projectId) throws IOException {
        ensureOpen();
        searcherCache.refreshIfCached(projectId);
    }

    public void refreshIfCached(UUID projectId, Path indexPath) throws IOException {
        ensureOpen();
        searcherCache.refreshIfCached(projectId, indexPath);
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
