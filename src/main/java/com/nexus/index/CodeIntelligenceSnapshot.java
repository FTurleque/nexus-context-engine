package com.nexus.index;

import java.util.List;
import java.util.Objects;

public record CodeIntelligenceSnapshot(
        String sourceProvider,
        List<IndexedSymbol> symbols,
        List<IndexedRelation> relations) {

    public CodeIntelligenceSnapshot {
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(relations, "relations");
        if (sourceProvider.isBlank()) {
            throw new IllegalArgumentException("sourceProvider ne doit pas être vide");
        }
        symbols = List.copyOf(symbols);
        relations = List.copyOf(relations);
        for (IndexedSymbol indexedSymbol : symbols) {
            if (!sourceProvider.equals(indexedSymbol.symbol().sourceProvider())) {
                throw new IllegalArgumentException("La provenance d'un symbole ne correspond pas au snapshot");
            }
        }
        for (IndexedRelation indexedRelation : relations) {
            if (!sourceProvider.equals(indexedRelation.relation().sourceProvider())) {
                throw new IllegalArgumentException("La provenance d'une relation ne correspond pas au snapshot");
            }
        }
    }

    public static CodeIntelligenceSnapshot empty(String sourceProvider) {
        return new CodeIntelligenceSnapshot(sourceProvider, List.of(), List.of());
    }
}
