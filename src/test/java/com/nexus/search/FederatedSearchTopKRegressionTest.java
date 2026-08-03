package com.nexus.search;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.DeterministicContextRanker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FederatedSearchTopKRegressionTest {

    @TempDir
    Path projectRoot;

    @Test
    void replenishesGlobalTopKAfterSamePathDiversification() throws Exception {
        ProjectDescriptor project = new ProjectDescriptor(
                UUID.randomUUID(),
                "demo",
                projectRoot,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);

        SearchStrategy strategy = (ignoredProject, ignoredQuery, retrievalLimit) -> List.of(
                new SearchCandidate(
                        "symbol:a",
                        CandidateType.SYMBOL,
                        projectRoot.resolve("src/A.java"),
                        null,
                        "A symbol",
                        Map.of(SearchSignals.LEXICAL, 1.0d)),
                new SearchCandidate(
                        "file:a",
                        CandidateType.FILE,
                        projectRoot.resolve("src/A.java"),
                        null,
                        "A file",
                        Map.of(SearchSignals.LEXICAL, 0.95d)),
                new SearchCandidate(
                        "file:b",
                        CandidateType.FILE,
                        projectRoot.resolve("src/B.java"),
                        null,
                        "B file",
                        Map.of(SearchSignals.LEXICAL, 0.90d)));

        SearchService searchService = new SearchService(
                List.of(strategy),
                List.of(),
                new DeterministicContextRanker());
        FederatedSearchService federated = new FederatedSearchService(searchService);

        List<FederatedSearchHit> hits = federated.search(List.of(project), "A", 2, false);

        assertEquals(2, hits.size());
        assertEquals(
                List.of("src/A.java", "src/B.java"),
                hits.stream()
                        .map(hit -> projectRoot.relativize(hit.rankedCandidate().candidate().path()).toString().replace('\\', '/'))
                        .toList());
    }
}
