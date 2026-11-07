package com.nexus.search;

import com.nexus.config.ResultLimitPolicy;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.FederationDeadline;
import com.nexus.project.FederationDeadlinePolicy;
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
    private final FederationDeadlinePolicy deadlinePolicy;

    public FederatedSearchService(SearchService searchService) {
        this(searchService, FederationDeadlinePolicy.fromEnvironment());
    }

    public FederatedSearchService(SearchService searchService, FederationDeadlinePolicy deadlinePolicy) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
    }

    public List<FederatedSearchHit> search(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain) throws IOException {
        return searchWithDiagnostics(projects, query, limit, explain).hits();
    }

    /**
     * Recherche fédérée sous échéance globale (BE-0064) : l'échéance est vérifiée avant
     * chaque projet, dans l'ordre du scope, sans parallélisme. Les projets non commencés
     * à l'échéance sont rendus, dans l'ordre du scope, par {@code unservedProjects}.
     */
    public FederatedSearchOutcome searchWithDiagnostics(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain) throws IOException {
        List<ProjectDescriptor> scope = FederatedScopePolicy.normalizeProjects(projects);
        String normalizedQuery = QueryPolicy.normalize(query);
        int validatedLimit = ResultLimitPolicy.validate(limit);
        scope.forEach(com.nexus.project.ProjectReadiness::requireReady);

        int localLimit = localRetrievalLimit(validatedLimit);
        List<OrderedFederatedHit> candidates = new ArrayList<>();
        // Un seul contexte pour toute la requête : le vecteur de requête est calculé
        // au plus une fois, quel que soit le nombre de projets (AUD-PRF-02).
        FederationDeadline deadline = deadlinePolicy.start();
        SearchQueryContext queryContext = SearchQueryContext.create(deadline);
        List<ProjectDescriptor> unserved = new ArrayList<>();
        int projectOrder = 0;
        for (ProjectDescriptor project : scope) {
            if (deadline.expired()) {
                unserved.add(project);
                continue;
            }
            List<RankedCandidate> projectResults = searchService.search(
                    project, normalizedQuery, localLimit, explain, queryContext);
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
        return new FederatedSearchOutcome(List.copyOf(diversified.values()), unserved);
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
