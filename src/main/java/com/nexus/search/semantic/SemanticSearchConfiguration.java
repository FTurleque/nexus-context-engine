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

    private static final SemanticSearchConfiguration DISABLED = new SemanticSearchConfiguration(null);

    private final EmbeddingProvider embeddingProvider;

    private SemanticSearchConfiguration(EmbeddingProvider embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public static SemanticSearchConfiguration disabled() {
        return DISABLED;
    }

    public static SemanticSearchConfiguration enabled(EmbeddingProvider embeddingProvider) {
        return new SemanticSearchConfiguration(Objects.requireNonNull(embeddingProvider, "embeddingProvider"));
    }

    public boolean enabled() {
        return embeddingProvider != null;
    }

    public Optional<EmbeddingProvider> embeddingProvider() {
        return Optional.ofNullable(embeddingProvider);
    }
}
