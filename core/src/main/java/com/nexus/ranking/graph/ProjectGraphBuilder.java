package com.nexus.ranking.graph;

import com.nexus.index.IndexRepository;
import com.nexus.search.ResultLimitPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Projection bornée du voisinage graphe nécessaire au ranking.
 *
 * <p>Le graphe projet complet n'est plus matérialisé ni mis en cache. Chaque
 * expansion demande au repository uniquement les arêtes touchant les paths
 * courants, avec une borne globale indépendante de la taille de l'index.</p>
 */
public final class ProjectGraphBuilder {

    public static final int MAX_NEIGHBOR_EDGES = ResultLimitPolicy.MAX_INTERNAL_RETRIEVAL_LIMIT;

    private final IndexRepository indexRepository;

    public ProjectGraphBuilder(IndexRepository indexRepository) {
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
    }

    public Map<String, Set<String>> neighbors(UUID projectId, Set<String> relativePaths) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(relativePaths, "relativePaths");
        if (relativePaths.isEmpty()) {
            return Map.of();
        }
        return indexRepository.findGraphNeighbors(projectId, relativePaths, MAX_NEIGHBOR_EDGES);
    }
}
