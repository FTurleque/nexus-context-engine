package com.nexus.search;

import com.nexus.paths.RepositoryPath;

import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;

import java.util.List;
import java.util.Objects;

public record SearchDocument(
        String relativePath,
        String language,
        FileCategory category,
        String content,
        List<CodeSymbol> symbols) {

    public SearchDocument {
        new RepositoryPath(relativePath);
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(symbols, "symbols");
        symbols = List.copyOf(symbols);
    }
}
