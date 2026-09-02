package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.search.LexicalSearchHit;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Index lexical de production conservant uniquement les readers/searchers chauds.
 *
 * <p>Les writes restent délégués à {@link LuceneSearchIndex}, donc operation-scoped.
 * Le cache est borné ; lorsqu'il est plein, la recherche retombe sur le chemin
 * historique afin de préserver une borne stricte sur les handles persistants.</p>
 */
public final class PersistentLuceneSearchIndex implements SearchIndex {

    static final int MAX_CACHED_PROJECTS = 100;

    private final NexusPaths paths;
    private final LuceneSearchIndex operationScoped;
    private final BoundedLuceneSearcherCache searcherCache;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PersistentLuceneSearchIndex(NexusPaths paths) {
        this(paths, MAX_CACHED_PROJECTS);
    }

    PersistentLuceneSearchIndex(NexusPaths paths, int cacheCapacity) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operationScoped = new LuceneSearchIndex(paths);
        this.searcherCache = new BoundedLuceneSearcherCache(cacheCapacity);
    }

    @Override
    public void applyChanges(UUID projectId, List<SearchDocument> documents, Set<String> removedPaths)
            throws IOException {
        ensureOpen();
        operationScoped.applyChanges(projectId, documents, removedPaths);
        searcherCache.refreshIfCached(projectId);
    }

    @Override
    public void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException {
        ensureOpen();
        // Repartir sans reader chaud rend aussi le rebuild sûr si le répertoire
        // doit un jour être remplacé plutôt que modifié en place.
        searcherCache.invalidate(projectId);
        operationScoped.rebuild(projectId, documents);
    }

    @Override
    public List<LexicalSearchHit> search(UUID projectId, String query, int limit) throws IOException {
        ensureOpen();
        LuceneSearchIndex.validateSearchRequest(projectId, query, limit);

        Path indexPath = paths.projectLuceneIndex(projectId);
        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        paths.ensurePrivateDirectory(indexPath);

        BoundedLuceneSearcherCache.SearchLookup<List<LexicalSearchHit>> lookup = searcherCache.search(
                projectId,
                indexPath,
                searcher -> LuceneSearchIndex.search(searcher, query, limit));
        return lookup.cached() ? lookup.value() : operationScoped.search(projectId, query, limit);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            searcherCache.close();
        }
    }

    int cachedProjectCount() {
        return searcherCache.cachedProjectCount();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Persistent lexical Lucene index is closed");
        }
    }
}
