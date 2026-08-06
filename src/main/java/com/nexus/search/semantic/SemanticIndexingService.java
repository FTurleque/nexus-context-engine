package com.nexus.search.semantic;

import com.nexus.index.CodeSymbol;
import com.nexus.search.SearchDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Construit l'index vectoriel dérivé uniquement lorsqu'un provider d'embeddings
 * et un index sémantique ont été explicitement composés.
 */
public final class SemanticIndexingService {

    public static final int DEFAULT_MAX_EMBEDDING_CHARS = 12_000;
    public static final int DEFAULT_EXCERPT_CHARS = 320;
    public static final int DEFAULT_BATCH_SIZE = 32;
    private static final int CONTENT_PROFILE_VERSION = 1;

    private final EmbeddingProvider embeddingProvider;
    private final SemanticSearchIndex semanticSearchIndex;
    private final int maxEmbeddingChars;
    private final int batchSize;

    public SemanticIndexingService(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex) {
        this(embeddingProvider, semanticSearchIndex, DEFAULT_MAX_EMBEDDING_CHARS, DEFAULT_BATCH_SIZE);
    }

    public SemanticIndexingService(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex,
            int maxEmbeddingChars) {
        this(embeddingProvider, semanticSearchIndex, maxEmbeddingChars, DEFAULT_BATCH_SIZE);
    }

    public SemanticIndexingService(
            EmbeddingProvider embeddingProvider,
            SemanticSearchIndex semanticSearchIndex,
            int maxEmbeddingChars,
            int batchSize) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.semanticSearchIndex = Objects.requireNonNull(semanticSearchIndex, "semanticSearchIndex");
        if (embeddingProvider.dimensions() <= 0) {
            throw new IllegalArgumentException("embeddingProvider dimensions must be greater than zero");
        }
        if (semanticSearchIndex.dimensions() != embeddingProvider.dimensions()) {
            throw new IllegalArgumentException(
                    "semantic index dimensions must match embedding provider dimensions");
        }
        if (maxEmbeddingChars <= 0) {
            throw new IllegalArgumentException("maxEmbeddingChars must be greater than zero");
        }
        if (batchSize <= 0 || batchSize > 256) {
            throw new IllegalArgumentException("batchSize must be between 1 and 256");
        }
        this.maxEmbeddingChars = maxEmbeddingChars;
        this.batchSize = batchSize;
    }

    public String modelId() {
        return embeddingProvider.modelId();
    }

    public String profileId() {
        return profileId(maxEmbeddingChars);
    }

    public static String defaultProfileId() {
        return profileId(DEFAULT_MAX_EMBEDDING_CHARS);
    }

    public boolean isCompatible(UUID projectId, String canonicalFingerprint) throws IOException {
        return semanticSearchIndex.isCompatible(projectId, provenance(canonicalFingerprint));
    }

    public void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException {
        semanticSearchIndex.rebuild(projectId, vectorize(documents));
    }

    public void rebuild(
            UUID projectId,
            String canonicalFingerprint,
            List<SearchDocument> documents) throws IOException {
        semanticSearchIndex.rebuild(
                projectId,
                provenance(canonicalFingerprint),
                vectorize(documents));
    }

    public void applyChanges(
            UUID projectId,
            List<SearchDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        semanticSearchIndex.applyChanges(projectId, vectorize(documents), removedRelativePaths);
    }

    public void applyChanges(
            UUID projectId,
            String canonicalFingerprint,
            List<SearchDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        semanticSearchIndex.applyChanges(
                projectId,
                provenance(canonicalFingerprint),
                vectorize(documents),
                removedRelativePaths);
    }

    private SemanticIndexProvenance provenance(String canonicalFingerprint) {
        return SemanticIndexProvenance.current(
                canonicalFingerprint,
                embeddingProvider,
                profileId());
    }

    private static String profileId(int maxEmbeddingChars) {
        return "content-v" + CONTENT_PROFILE_VERSION
                + ";maxEmbeddingChars=" + maxEmbeddingChars
                + ";excerptChars=" + DEFAULT_EXCERPT_CHARS;
    }

    private List<SemanticVectorDocument> vectorize(List<SearchDocument> documents) throws IOException {
        Objects.requireNonNull(documents, "documents");
        if (documents.isEmpty()) {
            return List.of();
        }
        List<SemanticVectorDocument> vectors = new ArrayList<>(documents.size());
        for (int start = 0; start < documents.size(); start += batchSize) {
            int end = Math.min(documents.size(), start + batchSize);
            List<SearchDocument> batch = documents.subList(start, end);
            List<String> texts = batch.stream().map(this::embeddingText).toList();
            List<float[]> embedded = Objects.requireNonNull(
                    embeddingProvider.embedAll(texts),
                    "embedding vectors");
            if (embedded.size() != batch.size()) {
                throw new IOException(
                        "Le provider d'embeddings " + embeddingProvider.modelId()
                                + " a produit " + embedded.size() + " vecteur(s) pour " + batch.size() + " document(s)");
            }
            for (int index = 0; index < batch.size(); index++) {
                SearchDocument document = batch.get(index);
                float[] vector = Objects.requireNonNull(embedded.get(index), "embedding vector");
                validateDimensions(vector);
                vectors.add(new SemanticVectorDocument(
                        document.relativePath(),
                        document.category(),
                        excerpt(document),
                        vector));
            }
        }
        return List.copyOf(vectors);
    }

    private void validateDimensions(float[] vector) throws IOException {
        if (vector.length != embeddingProvider.dimensions()) {
            throw new IOException(
                    "Le provider d'embeddings " + embeddingProvider.modelId()
                            + " a produit un vecteur de dimension " + vector.length
                            + " au lieu de " + embeddingProvider.dimensions());
        }
    }

    private String embeddingText(SearchDocument document) {
        StringBuilder text = new StringBuilder();
        text.append("path: ").append(document.relativePath()).append('\n');
        text.append("language: ").append(document.language()).append('\n');
        if (!document.symbols().isEmpty()) {
            text.append("symbols:");
            for (CodeSymbol symbol : document.symbols()) {
                text.append(' ').append(symbol.name());
            }
            text.append('\n');
        }
        int remaining = Math.max(0, maxEmbeddingChars - text.length());
        if (remaining > 0) {
            String content = document.content();
            text.append(content, 0, Math.min(content.length(), remaining));
        }
        return text.toString();
    }

    private static String excerpt(SearchDocument document) {
        String normalized = document.content().replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return document.relativePath();
        }
        return normalized.substring(0, Math.min(normalized.length(), DEFAULT_EXCERPT_CHARS));
    }
}
