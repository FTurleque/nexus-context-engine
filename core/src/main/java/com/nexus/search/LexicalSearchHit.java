package com.nexus.search;

import com.nexus.paths.RepositoryPath;

import com.nexus.index.FileCategory;

import java.util.Objects;

public record LexicalSearchHit(
        String relativePath,
        String language,
        FileCategory category,
        double score) {

    public LexicalSearchHit {
        new RepositoryPath(relativePath);
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(category, "category");
        if (score < 0.0d) {
            throw new IllegalArgumentException("score must be greater than or equal to zero");
        }
    }
}
