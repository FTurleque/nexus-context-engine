package com.nexus.index;

import java.util.Objects;

public record IndexedRelation(String relativePath, SymbolRelation relation) {

    public IndexedRelation {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(relation, "relation");
    }
}
