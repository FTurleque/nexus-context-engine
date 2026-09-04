package com.nexus.search;

import com.nexus.project.FederatedScopePolicy;
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
 * candidat est conservé. Aucune déduplication inter-projets n'est appliquée.</p>
 *
 * <p>La récupération locale est volontairement supérieure au top-K final afin
 * que la diversification par chemin ne sous-remplisse pas le résultat alors que
 * des candidats distincts existent juste après le cut-off local.</p>
 */
public final class FederatedSearchService {

    private static final int MIN_LOCAL_RETRIEVAL = 20;
    private static final int MAX_LOCAL_RETRIEVAL = 500;
    private static final int LOCAL_OVERFETCH_FACTOR = 4;

    private final SearchService searchService;

    public FederatedSearchService(SearchService searchService) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
    }

    public List<FederatedSearchHit> search(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain) throws IOException {
        List<ProjectDescriptor> scope = FederatedScopePolicy.normalizeProjects(projects);
        String normalizedQuery = QueryPolicy.normalize(query);
        int validatedLimit = ResultLimitPolicy.validate(limit);

        int localLimit = localRetrievalLimit(validatedLimit);
        List<OrderedFederatedHit> candidates = new ArrayList<>();
        int projectOrder = 0;
        for (ProjectDescriptor project : scope) {
            List<RankedCandidate> projectResults = searchService.search(
                    project, normalizedQuery, localLimit, explain);
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
            if (diversified.size() >= validatedLimit) {
                break;
            }
        }
        return List.copyOf(diversified.values());
    }

    private static int localRetrievalLimit(int limit) {
        if (limit >= MAX_LOCAL_RETRIEVAL / LOCAL_OVERFETCH_FACTOR) {
            return MAX_LOCAL_RETRIEVAL;
        }
        return Math.clamp(
                (long) limit * LOCAL_OVERFETCH_FACTOR,
                MIN_LOCAL_RETRIEVAL,
                MAX_LOCAL_RETRIEVAL);
    }

    private record OrderedFederatedHit(
            FederatedSearchHit hit,
            int projectOrder,
            int localOrder) {
    }

    private record ProjectPathKey(UUID projectId, Path path) {
    }
}
