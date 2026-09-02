package com.nexus.index;

import java.util.Objects;

public record SymbolRelation(
        RelationKind kind,
        String source,
        String target,
        double confidence,
        String sourceProvider) {

    public static final double DEFAULT_CONFIDENCE = 1.0d;
    public static final String DEFAULT_SOURCE_PROVIDER = "javaparser";

    public SymbolRelation(RelationKind kind, String source, String target) {
        this(kind, source, target, DEFAULT_CONFIDENCE, DEFAULT_SOURCE_PROVIDER);
    }

    public SymbolRelation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        if (!Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new IllegalArgumentException("confidence doit être comprise entre 0 et 1");
        }
        if (sourceProvider.isBlank()) {
            throw new IllegalArgumentException("sourceProvider ne doit pas être vide");
        }
    }
}
