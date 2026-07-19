package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.index.CodeSymbol;
import io.github.fturleque.nexus.index.FileCategory;

import java.util.List;
import java.util.Objects;

public record SearchDocument(
        String relativePath,
        String language,
        FileCategory category,
        String content,
        List<CodeSymbol> symbols) {

    public SearchDocument {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(symbols, "symbols");
        symbols = List.copyOf(symbols);
    }
}
