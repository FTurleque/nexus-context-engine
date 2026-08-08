package com.nexus.ranking.graph;

import com.nexus.index.IndexRepository;
import com.nexus.index.SymbolRelation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Matérialise le graphe relationnel une seule fois par génération d'index.
 * SQLite reste la source canonique ; le cache est purement dérivé et peut être
 * reconstruit à tout moment.
 */
public final class ProjectGraphBuilder {

    private final IndexRepository indexRepository;
    private final ConcurrentMap<UUID, CachedGraph> cache = new ConcurrentHashMap<>();

    public ProjectGraphBuilder(IndexRepository indexRepository) {
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
    }

    public ProjectGraph build(UUID projectId) {
        long generation = indexRepository.generation(projectId);
        CachedGraph cached = cache.get(projectId);
        if (cached != null && cached.generation() == generation) {
            return cached.graph();
        }
        return cache.compute(projectId, (ignored, current) -> {
            if (current != null && current.generation() == generation) {
                return current;
            }
            return new CachedGraph(generation, buildFresh(projectId));
        }).graph();
    }

    public void invalidate(UUID projectId) {
        cache.remove(projectId);
    }

    private ProjectGraph buildFresh(UUID projectId) {
        Map<String, String> typeOwners = indexRepository.findTypeOwners(projectId);
        Map<String, Set<String>> edges = new LinkedHashMap<>();

        for (SymbolRelation relation : indexRepository.findImportRelations(projectId)) {
            String targetPath = resolveImportedType(typeOwners, relation.target());
            if (targetPath == null || targetPath.equals(relation.source())) {
                continue;
            }
            edges.computeIfAbsent(relation.source(), ignored -> new LinkedHashSet<>()).add(targetPath);
        }

        return ProjectGraph.undirected(edges);
    }

    private static String resolveImportedType(Map<String, String> owners, String targetRef) {
        String candidate = targetRef;
        while (!candidate.isBlank()) {
            String owner = owners.get(candidate);
            if (owner != null) {
                return owner;
            }
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) {
                return null;
            }
            candidate = candidate.substring(0, separator);
        }
        return null;
    }

    private record CachedGraph(long generation, ProjectGraph graph) {
    }
}
