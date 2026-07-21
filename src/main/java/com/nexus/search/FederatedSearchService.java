package com.nexus.search;

import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestre une recherche sur plusieurs projets NEXUS sans modifier les moteurs
 * de recherche propres à chaque projet.
 *
 * <p>Chaque projet est recherché indépendamment via {@link SearchService}. Les
 * résultats sont ensuite fusionnés de manière déterministe en conservant le
 * {@link ProjectDescriptor} d'origine. Deux candidats du même projet qui pointent
 * vers le même chemin sont diversifiés après le tri global : seul le meilleur
 * candidat est conservé. Aucune déduplication inter-projets n'est appliquée :
 * deux chemins identiques appartenant à deux projets distincts restent deux
 * résultats différents.</p>
 */
public final class FederatedSearchService {

    private final SearchService searchService;

    public FederatedSearchService(SearchService searchService) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
    }

    public List<FederatedSearchHit> search(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain) throws IOException {
        Objects.requireNonNull(projects, "projects");
        Objects.requireNonNull(query, "query");
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("projects must not be empty");
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        Map<UUID, ProjectDescriptor> uniqueProjects = new LinkedHashMap<>();
        for (ProjectDescriptor project : projects) {
            ProjectDescriptor nonNullProject = Objects.requireNonNull(project, "project");
            uniqueProjects.putIfAbsent(nonNullProject.id(), nonNullProject);
        }

        List<OrderedFederatedHit> candidates = new ArrayList<>();
        int projectOrder = 0;
        for (ProjectDescriptor project : uniqueProjects.values()) {
            List<RankedCandidate> projectResults = searchService.search(project, query, limit, explain);
            for (int localOrder = 0; localOrder < projectResults.size(); localOrder++) {
                candidates.add(new OrderedFederatedHit(
                        new FederatedSearchHit(project, projectResults.get(localOrder)),
                        projectOrder,
                        localOrder));
            }
            projectOrder++;
        }

        List<OrderedFederatedHit> ordered = candidates.stream()
                .sorted(Comparator
                        .comparingDouble((OrderedFederatedHit hit) -> hit.hit().rankedCandidate().score()).reversed()
                        .thenComparingInt(OrderedFederatedHit::projectOrder)
                        .thenComparingInt(OrderedFederatedHit::localOrder))
                .toList();

        Map<ProjectPathKey, FederatedSearchHit> diversified = new LinkedHashMap<>();
        for (OrderedFederatedHit candidate : ordered) {
            FederatedSearchHit hit = candidate.hit();
            ProjectPathKey key = new ProjectPathKey(
                    hit.project().id(),
                    hit.rankedCandidate().candidate().path().toAbsolutePath().normalize());
            diversified.putIfAbsent(key, hit);
            if (diversified.size() >= limit) {
                break;
            }
        }
        return List.copyOf(diversified.values());
    }

    private record OrderedFederatedHit(
            FederatedSearchHit hit,
            int projectOrder,
            int localOrder) {
    }

    private record ProjectPathKey(UUID projectId, Path path) {
    }
}
