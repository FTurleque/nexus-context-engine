package com.nexus.index;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        Map<ExternalSymbolIdentity, IndexedSymbol> canonicalSymbols = new LinkedHashMap<>();
        for (IndexedSymbol indexedSymbol : symbols) {
            if (!sourceProvider.equals(indexedSymbol.symbol().sourceProvider())) {
                throw new IllegalArgumentException("La provenance d'un symbole ne correspond pas au snapshot");
            }
            canonicalSymbols.putIfAbsent(ExternalSymbolIdentity.of(indexedSymbol), indexedSymbol);
        }
        symbols = List.copyOf(canonicalSymbols.values());

        Map<ExternalRelationIdentity, IndexedRelation> canonicalRelations = new LinkedHashMap<>();
        for (IndexedRelation indexedRelation : relations) {
            if (!sourceProvider.equals(indexedRelation.relation().sourceProvider())) {
                throw new IllegalArgumentException("La provenance d'une relation ne correspond pas au snapshot");
            }
            canonicalRelations.merge(
                    ExternalRelationIdentity.of(indexedRelation),
                    indexedRelation,
                    CodeIntelligenceSnapshot::preferHigherConfidence);
        }
        relations = List.copyOf(canonicalRelations.values());
    }

    public static CodeIntelligenceSnapshot empty(String sourceProvider) {
        return new CodeIntelligenceSnapshot(sourceProvider, List.of(), List.of());
    }

    private static IndexedRelation preferHigherConfidence(IndexedRelation current, IndexedRelation candidate) {
        return candidate.relation().confidence() > current.relation().confidence() ? candidate : current;
    }
}
