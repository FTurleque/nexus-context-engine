package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.index.FileCategory;

import java.util.Objects;

public record LexicalSearchHit(
        String relativePath,
        String language,
        FileCategory category,
        double score) {

    public LexicalSearchHit {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(category, "category");
        if (score < 0.0d) {
            throw new IllegalArgumentException("score must be greater than or equal to zero");
        }
    }
}
