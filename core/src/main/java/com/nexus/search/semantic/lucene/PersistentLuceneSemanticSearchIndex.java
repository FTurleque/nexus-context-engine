package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.lucene.BoundedLuceneSearcherCache;
import com.nexus.search.semantic.SemanticIndexProvenance;
import com.nexus.search.semantic.SemanticSearchHit;
import com.nexus.search.semantic.SemanticSearchIndex;
import com.nexus.search.semantic.SemanticVectorDocument;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Index sémantique de production conservant uniquement les readers/searchers chauds.
 *
 * <p>Les mutations et le recovery restent délégués à
 * {@link LuceneSemanticSearchIndex}. En particulier, un rebuild invalide d'abord
 * le reader persistant afin que la purge du cache dérivé reste sûre sous Windows.</p>
 */
public final class PersistentLuceneSemanticSearchIndex implements SemanticSearchIndex {

    static final int MAX_CACHED_PROJECTS = 100;

    private static final String PATH_FIELD = "path";
    private static final String CATEGORY_FIELD = "category";
    private static final String EXCERPT_FIELD = "excerpt";
    private static final String VECTOR_FIELD = "embedding";

    private final NexusPaths paths;
    private final int dimensions;
    private final LuceneSemanticSearchIndex operationScoped;
    private final BoundedLuceneSearcherCache searcherCache;
    private final AtomicBoolean closed = new AtomicBoolean();

    public PersistentLuceneSemanticSearchIndex(NexusPaths paths, int dimensions) {
        this(paths, dimensions, MAX_CACHED_PROJECTS);
    }

    PersistentLuceneSemanticSearchIndex(NexusPaths paths, int dimensions, int cacheCapacity) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.operationScoped = new LuceneSemanticSearchIndex(paths, dimensions);
        this.dimensions = this.operationScoped.dimensions();
        this.searcherCache = new BoundedLuceneSearcherCache(cacheCapacity);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public boolean isCompatible(UUID projectId, SemanticIndexProvenance provenance) throws IOException {
        ensureOpen();
        return operationScoped.isCompatible(projectId, provenance);
    }

    @Override
    public void rebuild(UUID projectId, List<SemanticVectorDocument> documents) throws IOException {
        ensureOpen();
        searcherCache.invalidate(projectId);
        operationScoped.rebuild(projectId, documents);
    }

    @Override
    public void rebuild(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents) throws IOException {
        ensureOpen();
        searcherCache.invalidate(projectId);
        operationScoped.rebuild(projectId, provenance, documents);
    }

    @Override
    public void applyChanges(
            UUID projectId,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        ensureOpen();
        operationScoped.applyChanges(projectId, documents, removedRelativePaths);
        searcherCache.refreshIfCached(projectId);
    }

    @Override
    public void applyChanges(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        ensureOpen();
        operationScoped.applyChanges(projectId, provenance, documents, removedRelativePaths);
        searcherCache.refreshIfCached(projectId);
    }

    @Override
    public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException {
        ensureOpen();
        Objects.requireNonNull(projectId, "projectId");
        validateVector(queryVector);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        Path indexPath = paths.projectSemanticLuceneIndex(projectId);
        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        paths.ensurePrivateDirectory(indexPath);

        BoundedLuceneSearcherCache.SearchLookup<List<SemanticSearchHit>> lookup = searcherCache.search(
                projectId,
                indexPath,
                searcher -> search(searcher, queryVector, limit));
        return lookup.cached() ? lookup.value() : operationScoped.search(projectId, queryVector, limit);
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

    private List<SemanticSearchHit> search(IndexSearcher searcher, float[] queryVector, int limit)
            throws IOException {
        int documents = searcher.getIndexReader().numDocs();
        if (documents == 0) {
            return List.of();
        }
        int k = Math.min(limit, documents);
        Query query = KnnFloatVectorField.newVectorQuery(VECTOR_FIELD, queryVector, k);
        TopDocs topDocs = searcher.search(query, k);
        List<SemanticSearchHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document document = searcher.storedFields().document(scoreDoc.doc);
            hits.add(new SemanticSearchHit(
                    document.get(PATH_FIELD),
                    FileCategory.valueOf(document.get(CATEGORY_FIELD)),
                    document.get(EXCERPT_FIELD),
                    clamp(scoreDoc.score)));
        }
        return List.copyOf(hits);
    }

    private void validateVector(float[] vector) {
        Objects.requireNonNull(vector, "vector");
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "vector dimension " + vector.length + " does not match index dimension " + dimensions);
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("vector values must be finite");
            }
            nonZero |= value != 0.0f;
        }
        if (!nonZero) {
            throw new IllegalArgumentException("vector must contain at least one non-zero value");
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Persistent semantic Lucene index is closed");
        }
    }
}
