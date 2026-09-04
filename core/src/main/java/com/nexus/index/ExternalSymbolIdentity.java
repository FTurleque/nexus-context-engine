package com.nexus.index;

import java.util.Objects;

/**
 * Canonical structural identity of one externally supplied symbol fact.
 *
 * <p>The identity deliberately includes provenance and every structural field persisted by NEXUS.
 * Two external facts are duplicates only when all of these components are identical.</p>
 */
public record ExternalSymbolIdentity(
        String relativePath,
        SymbolKind kind,
        String name,
        String qualifiedName,
        String signature,
        int startLine,
        int endLine,
        String sourceProvider) {

    public ExternalSymbolIdentity {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
    }

    public static ExternalSymbolIdentity of(IndexedSymbol indexedSymbol) {
        Objects.requireNonNull(indexedSymbol, "indexedSymbol");
        CodeSymbol symbol = indexedSymbol.symbol();
        return new ExternalSymbolIdentity(
                indexedSymbol.relativePath(),
                symbol.kind(),
                symbol.name(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.startLine(),
                symbol.endLine(),
                symbol.sourceProvider());
    }
}
