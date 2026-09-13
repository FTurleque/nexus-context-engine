package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.semantic.SemanticIndexProvenance;
import com.nexus.search.semantic.SemanticSearchHit;
import com.nexus.search.semantic.SemanticSearchIndex;
import com.nexus.search.semantic.SemanticVectorDocument;
import com.nexus.security.SafeFileIO;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexFormatTooNewException;
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
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Index vectoriel local dérivé basé sur les capacités kNN natives de Lucene.
 */
public final class LuceneSemanticSearchIndex implements SemanticSearchIndex {

    private static final String PROVENANCE_ARGUMENT = "provenance";
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
        Objects.requireNonNull(provenance, PROVENANCE_ARGUMENT);
        Path indexPath = indexPath(projectId);
        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        paths.ensurePrivateDirectory(indexPath);
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
        rebuildInternal(projectId, Objects.requireNonNull(provenance, PROVENANCE_ARGUMENT), documents);
    }

    private void rebuildInternal(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(documents, "documents");
        Path indexPath = indexPath(projectId);
        paths.ensurePrivateDirectory(indexPath);
        try {
            rebuildDirectory(indexPath, provenance, documents);
        } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException | EOFException failure) {
            // CREATE preserves healthy commit generations, but cannot read a
            // corrupt commit. Publish recovery elsewhere without deleting files
            // still mapped by independent readers (especially on Windows).
            Path root = paths.projectSemanticLuceneIndex(projectId);
            Path recovery = root.resolve("recovery-" + UUID.randomUUID());
            paths.ensurePrivateDirectory(recovery);
            rebuildDirectory(recovery, provenance, documents);
            publishRecovery(root, recovery);
        }
    }

    private void rebuildDirectory(
            Path indexPath,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents) throws IOException {
        try (Directory directory = FSDirectory.open(indexPath)) {
            IndexWriterConfig configuration = new IndexWriterConfig()
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                    .setCommitOnClose(false);
            try (IndexWriter writer = new IndexWriter(directory, configuration)) {
                for (SemanticVectorDocument document : documents) {
                    writer.addDocument(toLuceneDocument(document));
                }
                applyProvenance(writer, provenance);
                writer.commit();
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
                Objects.requireNonNull(provenance, PROVENANCE_ARGUMENT),
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
        Path indexPath = indexPath(projectId);
        paths.ensurePrivateDirectory(indexPath);
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
        validateSearchRequest(projectId, queryVector, limit);

        Path indexPath = indexPath(projectId);
        if (!Files.exists(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        paths.ensurePrivateDirectory(indexPath);

        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                return search(new IndexSearcher(reader), queryVector, limit);
            }
        }
    }

    void validateSearchRequest(UUID projectId, float[] queryVector, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        validateVector(queryVector);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
    }

    List<SemanticSearchHit> search(IndexSearcher searcher, float[] queryVector, int limit) throws IOException {
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

    Path indexPath(UUID projectId) throws IOException {
        Path root = paths.projectSemanticLuceneIndex(projectId);
        Path pointer = root.resolve("current-generation");
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return root;
        }
        paths.ensurePrivateDirectory(root);
        String generation;
        try (var reader = SafeFileIO.newBufferedReaderNoFollow(pointer, StandardCharsets.UTF_8, 128)) {
            generation = reader.readLine();
            if (generation == null || !generation.matches("recovery-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                    || reader.readLine() != null) {
                throw new IOException("Pointeur de récupération sémantique invalide");
            }
        }
        Path selected = root.resolve(generation);
        if (!Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Génération sémantique absente : " + selected);
        }
        paths.ensurePrivateDirectory(selected);
        return selected;
    }

    private void publishRecovery(Path root, Path recovery) throws IOException {
        Path temporaryPointer = root.resolve("current-generation-" + UUID.randomUUID() + ".tmp");
        paths.ensurePrivateFile(temporaryPointer);
        try {
            Files.writeString(temporaryPointer, recovery.getFileName().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            Files.move(temporaryPointer, root.resolve("current-generation"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryPointer);
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
        return Math.clamp(value, 0.0d, 1.0d);
    }
}
