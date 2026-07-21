package com.nexus.search.semantic;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port d'un index vectoriel local dérivé et reconstructible.
 */
public interface SemanticSearchIndex {

    /**
     * Dimension attendue par l'index.
     */
    int dimensions();

    /**
     * Reconstruit entièrement l'index vectoriel d'un projet.
     */
    void rebuild(UUID projectId, List<SemanticVectorDocument> documents) throws IOException;

    /**
     * Applique un delta de documents et de chemins supprimés.
     */
    void applyChanges(
            UUID projectId,
            List<SemanticVectorDocument> documents,
            Set<String> removedRelativePaths) throws IOException;

    /**
     * Recherche les documents sémantiquement les plus proches pour un projet.
     */
    List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException;
}
