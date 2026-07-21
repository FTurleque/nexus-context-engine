package com.nexus.search.semantic;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Port de lecture d'un index vectoriel dérivé et reconstructible.
 */
public interface SemanticSearchIndex {

    /**
     * Dimension attendue par l'index.
     */
    int dimensions();

    /**
     * Recherche les documents sémantiquement les plus proches pour un projet.
     */
    List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException;
}
