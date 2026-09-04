package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.search.lucene.PersistentLuceneReaderSupport;
import com.nexus.search.semantic.SemanticIndexProvenance;
import com.nexus.search.semantic.SemanticSearchHit;
import com.nexus.search.semantic.SemanticSearchIndex;
import com.nexus.search.semantic.SemanticVectorDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Index sémantique de production conservant uniquement les readers/searchers chauds.
 *
 * <p>Les mutations et le recovery restent délégués à
 * {@link LuceneSemanticSearchIndex}. En particulier, un rebuild invalide d'abord
 * le reader persistant afin que la purge du cache dérivé reste sûre sous Windows.</p>
 */
public final class PersistentLuceneSemanticSearchIndex implements SemanticSearchIndex {

    static final int MAX_CACHED_PROJECTS = 100;

    private final NexusPaths paths;
    private final int dimensions;
    private final LuceneSemanticSearchIndex operationScoped;
    private final PersistentLuceneReaderSupport readers;

    public PersistentLuceneSemanticSearchIndex(NexusPaths paths, int dimensions) {
        this(paths, dimensions, MAX_CACHED_PROJECTS);
    }

    PersistentLuceneSemanticSearchIndex(NexusPaths paths, int dimensions, int cacheCapacity) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operationScoped = new LuceneSemanticSearchIndex(paths, dimensions);
        this.dimensions = this.operationScoped.dimensions();
        this.readers = new PersistentLuceneReaderSupport(paths, cacheCapacity);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public boolean isCompatible(UUID projectId, SemanticIndexProvenance provenance) throws IOException {
        readers.ensureOpen();
        return operationScoped.isCompatible(projectId, provenance);
    }

    @Override
    public void rebuild(UUID projectId, List<SemanticVectorDocument> documents) throws IOException {
        rebuildInternal(projectId, null, documents);
    }

    @Override
    public void rebuild(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents) throws IOException {
        rebuildInternal(projectId, Objects.requireNonNull(provenance, "provenance"), documents);
    }

    private void rebuildInternal(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents) throws IOException {
        readers.ensureOpen();
        readers.invalidate(projectId);
        if (provenance == null) {
            operationScoped.rebuild(projectId, documents);
        } else {
            operationScoped.rebuild(projectId, provenance, documents);
        }
    }

    @Override
    public void applyChanges(
            UUID projectId,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        applyChangesInternal(projectId, null, documents, removedRelativePaths);
    }

    @Override
    public void applyChanges(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        applyChangesInternal(
                projectId,
                Objects.requireNonNull(provenance, "provenance"),
                documents,
                removedRelativePaths);
    }

    private void applyChangesInternal(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        readers.ensureOpen();
        if (provenance == null) {
            operationScoped.applyChanges(projectId, documents, removedRelativePaths);
        } else {
            operationScoped.applyChanges(projectId, provenance, documents, removedRelativePaths);
        }
        readers.refreshIfCached(projectId);
    }

    @Override
    public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException {
        readers.ensureOpen();
        operationScoped.validateSearchRequest(projectId, queryVector, limit);
        Path indexPath = paths.projectSemanticLuceneIndex(projectId);
        return readers.search(
                projectId,
                indexPath,
                searcher -> operationScoped.search(searcher, queryVector, limit),
                () -> operationScoped.search(projectId, queryVector, limit));
    }

    @Override
    public void close() throws IOException {
        readers.close();
    }

    int cachedProjectCount() {
        return readers.cachedProjectCount();
    }
}
