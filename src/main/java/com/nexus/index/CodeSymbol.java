package com.nexus.index;

import java.util.Objects;

public record CodeSymbol(
        SymbolKind kind,
        String name,
        String qualifiedName,
        String signature,
        int startLine,
        int endLine) {

    public CodeSymbol {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(signature, "signature");
    }
}
