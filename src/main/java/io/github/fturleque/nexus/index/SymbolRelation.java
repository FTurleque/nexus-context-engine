package io.github.fturleque.nexus.index;

import java.util.Objects;

public record SymbolRelation(RelationKind kind, String source, String target) {

    public SymbolRelation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
