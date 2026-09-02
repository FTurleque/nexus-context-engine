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
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine doit être supérieur ou égal à 1");
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException("endLine doit être supérieur ou égal à startLine");
        }
    }

    public static boolean isStructurallyValidRange(int startLine, int endLine) {
        return startLine >= 1 && endLine >= startLine;
    }

    public static boolean isWithinLineCount(int startLine, int endLine, int lineCount) {
        return lineCount >= 0
                && isStructurallyValidRange(startLine, endLine)
                && endLine <= lineCount;
    }
}
