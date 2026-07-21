package com.nexus.search.semantic;

import java.util.Objects;
import java.util.Optional;

/**
 * Configuration explicite de la capacité sémantique.
 *
 * <p>La configuration par défaut est désactivée : aucun provider n'est appelé
 * tant que l'appelant ne fournit pas explicitement un {@link EmbeddingProvider}.</p>
 */
public final class SemanticSearchConfiguration {

    public static final double DEFAULT_SEMANTIC_RRF_WEIGHT = 1.0d;
    public static final double MAX_SEMANTIC_RRF_WEIGHT = 10.0d;

    private static final SemanticSearchConfiguration DISABLED =
            new SemanticSearchConfiguration(null, DEFAULT_SEMANTIC_RRF_WEIGHT);

    private final EmbeddingProvider embeddingProvider;
    private final double semanticRrfWeight;

    private SemanticSearchConfiguration(EmbeddingProvider embeddingProvider, double semanticRrfWeight) {
        if (!Double.isFinite(semanticRrfWeight)
                || semanticRrfWeight <= 0.0d
                || semanticRrfWeight > MAX_SEMANTIC_RRF_WEIGHT) {
            throw new IllegalArgumentException(
                    "semanticRrfWeight must be greater than 0.0 and at most " + MAX_SEMANTIC_RRF_WEIGHT);
        }
        this.embeddingProvider = embeddingProvider;
        this.semanticRrfWeight = semanticRrfWeight;
    }

    public static SemanticSearchConfiguration disabled() {
        return DISABLED;
    }

    public static SemanticSearchConfiguration enabled(EmbeddingProvider embeddingProvider) {
        return enabled(embeddingProvider, DEFAULT_SEMANTIC_RRF_WEIGHT);
    }

    public static SemanticSearchConfiguration enabled(
            EmbeddingProvider embeddingProvider,
            double semanticRrfWeight) {
        return new SemanticSearchConfiguration(
                Objects.requireNonNull(embeddingProvider, "embeddingProvider"),
                semanticRrfWeight);
    }

    public boolean enabled() {
        return embeddingProvider != null;
    }

    public Optional<EmbeddingProvider> embeddingProvider() {
        return Optional.ofNullable(embeddingProvider);
    }

    public double semanticRrfWeight() {
        return semanticRrfWeight;
    }
}