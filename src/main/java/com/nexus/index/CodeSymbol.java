package com.nexus.index;

import java.util.Objects;

public record CodeSymbol(
        SymbolKind kind,
        String name,
        String qualifiedName,
        String signature,
        int startLine,
        int endLine,
        String sourceProvider) {

    public static final String DEFAULT_SOURCE_PROVIDER = "javaparser";

    public CodeSymbol(
            SymbolKind kind,
            String name,
            String qualifiedName,
            String signature,
            int startLine,
            int endLine) {
        this(kind, name, qualifiedName, signature, startLine, endLine, DEFAULT_SOURCE_PROVIDER);
    }

    public CodeSymbol {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        if (sourceProvider.isBlank()) {
            throw new IllegalArgumentException("sourceProvider ne doit pas être vide");
        }
    }
}
