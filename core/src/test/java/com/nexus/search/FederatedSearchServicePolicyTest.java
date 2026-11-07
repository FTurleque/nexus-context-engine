package com.nexus.search;

import com.nexus.config.ResultLimitPolicy;
import com.nexus.project.FederationDeadlinePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.candidate.CandidateType;
import com.nexus.search.candidate.SearchCandidate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedSearchServicePolicyTest {

    private final SearchService searchService = new SearchService(
            List.of(),
            List.of(),
            (request, candidates) -> List.of());
    private final ProjectDescriptor project = new ProjectDescriptor(
            UUID.randomUUID(),
            "policy-test",
            Path.of(".").toAbsolutePath().normalize(),
            ProjectSourceType.LOCAL,
            Set.of("java"),
            Set.of(),
            null,
            IndexStatus.READY);

    @Test
    void rejectsPublicLimitAbovePolicyBeforeAnyProjectSearch() {
        FederatedSearchService federated = new FederatedSearchService(searchService);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> federated.search(
                List.of(project),
                "query",
                ResultLimitPolicy.MAX_RESULT_LIMIT + 1,
                false));

        assertTrue(failure.getMessage().contains("limit must not exceed " + ResultLimitPolicy.MAX_RESULT_LIMIT),
                failure.getMessage());
    }

    @Test
    void rejectsOversizedQueryAtFederatedServiceBoundary() {
        FederatedSearchService federated = new FederatedSearchService(searchService);
        String oversized = "x".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES + 1);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> federated.search(
                List.of(project),
                oversized,
                10,
                false));

        assertTrue(failure.getMessage().contains("limite de " + QueryPolicy.MAX_QUERY_UTF8_BYTES),
                failure.getMessage());
    }

    @Test
    void stopsStartingProjectsAtTheGlobalDeadlineAndNamesUnservedProjectsInScopeOrder() throws Exception {
        List<ProjectDescriptor> scope = fiveProjects();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        List<String> searched = new ArrayList<>();
        FederatedSearchService federated = new FederatedSearchService(
                searchServiceAdvancing(now, searched),
                new FederationDeadlinePolicy(Duration.ofSeconds(15), now::get));

        FederatedSearchOutcome outcome = federated.searchWithDiagnostics(scope, "needle", 10, false);

        assertEquals(List.of("a", "b"), searched);
        assertEquals(
                List.of("c", "d", "e"),
                outcome.unservedProjects().stream().map(ProjectDescriptor::name).toList());
        assertEquals(
                List.of("a", "b"),
                outcome.hits().stream().map(hit -> hit.project().name()).sorted().toList());
    }

    @Test
    void servesEveryProjectWithoutDiagnosticAndSameHitsWhenTheDeadlineIsNotReached() throws Exception {
        List<ProjectDescriptor> scope = fiveProjects();
        AtomicReference<Instant> frozen = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        FederatedSearchService withDeadline = new FederatedSearchService(
                searchServiceAdvancing(new AtomicReference<>(frozen.get()), new ArrayList<>()),
                new FederationDeadlinePolicy(Duration.ofSeconds(15), frozen::get));
        FederatedSearchService withoutDeadline = new FederatedSearchService(
                searchServiceAdvancing(new AtomicReference<>(frozen.get()), new ArrayList<>()));

        FederatedSearchOutcome outcome = withDeadline.searchWithDiagnostics(scope, "needle", 10, false);

        assertTrue(outcome.unservedProjects().isEmpty());
        assertEquals(5, outcome.hits().size());
        assertEquals(
                withoutDeadline.search(scope, "needle", 10, false).stream()
                        .map(hit -> hit.project().name()).toList(),
                outcome.hits().stream().map(hit -> hit.project().name()).toList());
    }

    /** Chaque recherche de projet consomme 10 secondes d'horloge simulée. */
    private static SearchService searchServiceAdvancing(AtomicReference<Instant> now, List<String> searched) {
        SearchStrategy strategy = (searchedProject, query, limit) -> {
            searched.add(searchedProject.name());
            now.updateAndGet(instant -> instant.plusSeconds(10));
            return List.of(new SearchCandidate(
                    "file:" + searchedProject.name(),
                    CandidateType.FILE,
                    searchedProject.rootPath().resolve(searchedProject.name() + ".txt"),
                    null,
                    "excerpt",
                    Map.of()));
        };
        return new SearchService(
                List.of(strategy),
                List.of(),
                (request, candidates) -> candidates.stream()
                        .map(candidate -> new RankedCandidate(candidate, 1.0d, Map.of(), List.of()))
                        .toList());
    }

    private List<ProjectDescriptor> fiveProjects() {
        return List.of("a", "b", "c", "d", "e").stream()
                .map(name -> new ProjectDescriptor(
                        UUID.randomUUID(),
                        name,
                        Path.of(".").toAbsolutePath().normalize().resolve(name),
                        ProjectSourceType.LOCAL,
                        Set.of("java"),
                        Set.of(),
                        null,
                        IndexStatus.READY))
                .toList();
    }
}
