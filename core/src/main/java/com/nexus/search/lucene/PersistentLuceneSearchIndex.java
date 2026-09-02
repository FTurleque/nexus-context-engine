package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.search.LexicalSearchHit;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
    private final PersistentLuceneReaderSupport readers;

    public PersistentLuceneSearchIndex(NexusPaths paths) {
        this(paths, MAX_CACHED_PROJECTS);
    }

    PersistentLuceneSearchIndex(NexusPaths paths, int cacheCapacity) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operationScoped = new LuceneSearchIndex(paths);
        this.readers = new PersistentLuceneReaderSupport(paths, cacheCapacity);
    }

    @Override
    public void applyChanges(UUID projectId, List<SearchDocument> documents, Set<String> removedPaths)
            throws IOException {
        readers.ensureOpen();
        operationScoped.applyChanges(projectId, documents, removedPaths);
        readers.refreshIfCached(projectId);
    }

    @Override
    public void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException {
        readers.ensureOpen();
        // Repartir sans reader chaud rend aussi le rebuild sûr si le répertoire
        // doit un jour être remplacé plutôt que modifié en place.
        readers.invalidate(projectId);
        operationScoped.rebuild(projectId, documents);
    }

    @Override
    public List<LexicalSearchHit> search(UUID projectId, String query, int limit) throws IOException {
        readers.ensureOpen();
        LuceneSearchIndex.validateSearchRequest(projectId, query, limit);
        Path indexPath = paths.projectLuceneIndex(projectId);
        return readers.search(
                projectId,
                indexPath,
                searcher -> LuceneSearchIndex.search(searcher, query, limit),
                () -> operationScoped.search(projectId, query, limit));
    }

    @Override
    public void close() throws IOException {
        readers.close();
    }

    int cachedProjectCount() {
        return readers.cachedProjectCount();
    }
}
