package com.nexus.search;

import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;

import java.io.IOException;
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
 * {@link ProjectDescriptor} d'origine. Aucune déduplication inter-projets n'est
 * appliquée : deux chemins identiques appartenant à deux projets distincts sont
 * deux résultats différents.</p>
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

        List<FederatedSearchHit> candidates = new ArrayList<>();
        for (ProjectDescriptor project : uniqueProjects.values()) {
            List<RankedCandidate> projectResults = searchService.search(project, query, limit, explain);
            for (RankedCandidate projectResult : projectResults) {
                candidates.add(new FederatedSearchHit(project, projectResult));
            }
        }

        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble((FederatedSearchHit hit) -> hit.rankedCandidate().score()).reversed()
                        .thenComparing(hit -> hit.project().id().toString())
                        .thenComparing(hit -> hit.rankedCandidate().candidate().type().name())
                        .thenComparing(hit -> hit.rankedCandidate().candidate().path().toString())
                        .thenComparing(hit -> hit.rankedCandidate().candidate().id()))
                .limit(limit)
                .toList();
    }
}
