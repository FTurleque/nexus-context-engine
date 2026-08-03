package com.nexus.index;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public interface IndexRepository {

    Map<String, IndexedFile> findFiles(UUID projectId);

    /**
     * Charge uniquement les fichiers demandés. L'implémentation par défaut
     * préserve la compatibilité des repositories de test ; SQLite surcharge
     * cette méthode avec une requête bornée.
     */
    default Map<String, IndexedFile> findFiles(UUID projectId, Set<String> relativePaths) {
        Objects.requireNonNull(relativePaths, "relativePaths");
        if (relativePaths.isEmpty()) {
            return Map.of();
        }
        Map<String, IndexedFile> selected = new LinkedHashMap<>();
        findFiles(projectId).forEach((path, file) -> {
            if (relativePaths.contains(path)) {
                selected.put(path, file);
            }
        });
        return Map.copyOf(selected);
    }

    List<IndexedSymbol> findSymbols(UUID projectId);

    /**
     * Recherche bornée de symboles. Les implémentations persistantes doivent
     * appliquer le filtre avant de matérialiser les résultats.
     */
    default List<IndexedSymbol> searchSymbols(UUID projectId, String query, int limit) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return findSymbols(projectId).stream()
                .filter(indexed -> indexed.symbol().name().toLowerCase(Locale.ROOT).contains(normalized)
                        || indexed.symbol().qualifiedName().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator
                        .comparing((IndexedSymbol indexed) -> !indexed.symbol().name().equalsIgnoreCase(query))
                        .thenComparing(indexed -> indexed.symbol().qualifiedName())
                        .thenComparing(IndexedSymbol::relativePath))
                .limit(limit)
                .toList();
    }

    List<SymbolRelation> findRelations(UUID projectId);

    /** Recherche bornée des relations portant sur un symbole. */
    default List<SymbolRelation> searchRelations(UUID projectId, String symbol, int limit) {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        String normalized = symbol.trim().toLowerCase(Locale.ROOT);
        return findRelations(projectId).stream()
                .filter(relation -> relation.source().toLowerCase(Locale.ROOT).contains(normalized)
                        || relation.target().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted(Comparator.comparing(SymbolRelation::kind)
                        .thenComparing(SymbolRelation::source)
                        .thenComparing(SymbolRelation::target))
                .limit(limit)
                .toList();
    }

    void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths);

    void replaceExternalCodeIntelligence(UUID projectId, CodeIntelligenceSnapshot snapshot);

    IndexStatistics statistics(UUID projectId);

    /**
     * Génération monotone de l'index canonique d'un projet. Elle sert à
     * invalider les vues dérivées en mémoire sans coupler celles-ci à SQLite.
     */
    default long generation(UUID projectId) {
        return 0L;
    }
}
