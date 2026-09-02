package com.nexus.search.semantic;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port d'un index vectoriel local dérivé et reconstructible.
 */
public interface SemanticSearchIndex extends AutoCloseable {

    /**
     * Dimension attendue par l'index.
     */
    int dimensions();

    /**
     * Indique si l'index persistant correspond exactement à la provenance
     * attendue. Les implémentations historiques restent volontairement
     * incompatibles par défaut afin de forcer une reconstruction sûre.
     */
    default boolean isCompatible(UUID projectId, SemanticIndexProvenance provenance) throws IOException {
        return false;
    }

    /**
     * Reconstruit entièrement l'index vectoriel d'un projet.
     */
    void rebuild(UUID projectId, List<SemanticVectorDocument> documents) throws IOException;

    /**
     * Reconstruit l'index et persiste sa provenance.
     */
    default void rebuild(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents) throws IOException {
        rebuild(projectId, documents);
    }

    /**
     * Applique un delta de documents et de chemins supprimés.
     */
    void applyChanges(
            UUID projectId,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException;

    /**
     * Applique un delta et actualise la provenance persistée.
     */
    default void applyChanges(
            UUID projectId,
            SemanticIndexProvenance provenance,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException {
        applyChanges(projectId, documents, removedRelativePaths);
    }

    /**
     * Recherche les documents sémantiquement les plus proches pour un projet.
     */
    List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException;

    /**
     * Libère les readers persistants éventuels. Les implémentations historiques
     * operation-scoped restent no-op.
     */
    @Override
    default void close() throws IOException {
        // no-op par défaut
    }
}
