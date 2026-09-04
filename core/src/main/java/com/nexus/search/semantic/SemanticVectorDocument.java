package com.nexus.search.semantic;

import com.nexus.index.FileCategory;

import java.util.Objects;

/**
 * Document vectoriel dérivé d'un fichier canonique NEXUS.
 */
public record SemanticVectorDocument(
        String relativePath,
        FileCategory category,
        String excerpt,
        float[] vector) {

    public SemanticVectorDocument {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(vector, "vector");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        if (vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }
}
