package com.nexus.search.semantic;

import com.nexus.index.FileCategory;

import java.util.Objects;

/**
 * Résultat brut d'un index vectoriel avant fusion avec les autres stratégies.
 */
public record SemanticSearchHit(
        String relativePath,
        FileCategory category,
        String excerpt,
        double score) {

    public SemanticSearchHit {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(excerpt, "excerpt");
        if (!Double.isFinite(score) || score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("score must be finite and between 0.0 and 1.0");
        }
    }
}
