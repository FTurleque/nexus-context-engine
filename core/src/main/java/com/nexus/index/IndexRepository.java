package com.nexus.index;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port de l'index canonique d'un projet. Chaque projection est déclarée abstraite : une
 * implémentation persistante filtre et borne côté stockage, sans repli qui matérialiserait tous
 * les symboles ou relations du projet.
 */
public interface IndexRepository {

    /** Ressources de lecture limitées à une opération ; aucun snapshot persistant implicite. */
    ReadSession openReadSession();

    interface ReadSession extends AutoCloseable {
        @Override void close();
    }

    Map<String, IndexedFile> findFiles(UUID projectId);

    /** Charge uniquement les fichiers demandés ; un ensemble vide donne une map vide. */
    Map<String, IndexedFile> findFiles(UUID projectId, Set<String> relativePaths);

    List<IndexedSymbol> findSymbols(UUID projectId);

    /**
     * Projection minimale nécessaire au graphe : nom qualifié d'un type vers son fichier
     * propriétaire, filtrée côté stockage.
     */
    Map<String, String> findTypeOwners(UUID projectId);

    /** Recherche bornée de symboles, filtrée avant matérialisation des résultats. */
    List<IndexedSymbol> searchSymbols(UUID projectId, String query, int limit);

    List<SymbolRelation> findRelations(UUID projectId);

    /** Projection minimale des imports utilisée pour construire le graphe de fichiers. */
    List<SymbolRelation> findImportRelations(UUID projectId);

    /**
     * Projection de voisinage bornée pour l'enrichissement de graphe : au plus {@code maxEdges}
     * arêtes autour de {@code relativePaths}, filtrées côté stockage.
     */
    Map<String, Set<String>> findGraphNeighbors(UUID projectId, Set<String> relativePaths, int maxEdges);

    /** Providers externes persistés, hors provider par défaut. */
    Set<String> findExternalProviders(UUID projectId);

    /** Recherche bornée des relations portant sur un symbole. */
    List<SymbolRelation> searchRelations(UUID projectId, String symbol, int limit);

    void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths);

    void replaceExternalCodeIntelligence(UUID projectId, CodeIntelligenceSnapshot snapshot);

    IndexStatistics statistics(UUID projectId);

    /**
     * Génération monotone de l'index canonique d'un projet. Elle sert à invalider les vues
     * dérivées en mémoire sans coupler celles-ci à SQLite.
     */
    long generation(UUID projectId);
}
