package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.semantic.SemanticIndexProvenance;
import com.nexus.search.semantic.SemanticSearchHit;
import com.nexus.search.semantic.SemanticSearchIndex;
import com.nexus.search.semantic.SemanticVectorDocument;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Index vectoriel local dérivé basé sur les capacités kNN natives de Lucene.
 */
public final class LuceneSemanticSearchIndex implements SemanticSearchIndex {

    private static final String PATH_FIELD = "path";
    private static final String CATEGORY_FIELD = "category";
    private static final String EXCERPT_FIELD = "excerpt";
    private static final String VECTOR_FIELD = "embedding";

    private final NexusPaths paths;
    private final int dimensions;

    public LuceneSemanticSearchIndex(NexusPaths paths, int dimensions) {
        this.paths = Objects.requireNonNull(paths, "paths");
        if (dimensions <= 0 || dimensions > 1024) {
            throw new IllegalArgumentException("dimensions must be between 1 and 1024");
        }
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public boolean isCompatible(UUID projectId, SemanticIndexProvenance provenance) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(provenance, "provenance");
        Path indexPath = paths.projectSemanticLuceneIndex(projectId);
        if (!Files.isDirectory(indexPath)) {
            return false;
        }
        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return false;
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                return provenance.matches(reader.getIndexCommit().getUserData());
            }
        }
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
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(documents, "documents");
        Path indexPath = paths.projectSemanticLuceneIndex(projectId);
        Files.createDirectories(indexPath);
        try (Directory directory = FSDirectory.open(indexPath)) {
            IndexWriterConfig configuration = new IndexWriterConfig()
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            try (IndexWriter writer = new IndexWriter(directory, configuration)) {
                for (SemanticVectorDocument document : documents) {
                    writer.addDocument(toLuceneDocument(document));
                }
                applyProvenance(writer, provenance);
            }
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
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(removedRelativePaths, "removedRelativePaths");
        Path indexPath = paths.projectSemanticLuceneIndex(projectId);
        Files.createDirectories(indexPath);
        try (Directory directory = FSDirectory.open(indexPath)) {
            IndexWriterConfig configuration = new IndexWriterConfig()
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try (IndexWriter writer = new IndexWriter(directory, configuration)) {
                for (String removedPath : removedRelativePaths) {
                    if (removedPath != null && !removedPath.isBlank()) {
                        writer.deleteDocuments(new Term(PATH_FIELD, removedPath));
                    }
                }
                for (SemanticVectorDocument document : documents) {
                    writer.deleteDocuments(new Term(PATH_FIELD, document.relativePath()));
                    writer.addDocument(toLuceneDocument(document));
                }
                applyProvenance(writer, provenance);
            }
        }
    }

    @Override
    public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        validateVector(queryVector);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        Path indexPath = paths.projectSemanticLuceneIndex(projectId);
        if (!Files.isDirectory(indexPath)) {
            return List.of();
        }

        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                if (reader.numDocs() == 0) {
                    return List.of();
                }
                IndexSearcher searcher = new IndexSearcher(reader);
                int k = Math.min(limit, reader.numDocs());
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
        }
    }

    private static void applyProvenance(IndexWriter writer, SemanticIndexProvenance provenance) {
        if (provenance != null) {
            writer.setLiveCommitData(provenance.asCommitData().entrySet());
        }
    }

    private Document toLuceneDocument(SemanticVectorDocument semanticDocument) {
        Objects.requireNonNull(semanticDocument, "semanticDocument");
        float[] vector = semanticDocument.vector();
        validateVector(vector);

        Document document = new Document();
        document.add(new StringField(PATH_FIELD, semanticDocument.relativePath(), Field.Store.YES));
        document.add(new StringField(CATEGORY_FIELD, semanticDocument.category().name(), Field.Store.YES));
        document.add(new StoredField(EXCERPT_FIELD, semanticDocument.excerpt()));
        document.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
        return document;
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
}
