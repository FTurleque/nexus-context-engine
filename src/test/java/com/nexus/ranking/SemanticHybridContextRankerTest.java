package com.nexus.ranking;

import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHybridContextRankerTest {

    @Test
    void delegatesExactlyToHistoricalRankingWithoutSemanticSignals() {
        SearchCandidate first = candidate(
                "file:src/SearchService.java",
                "src/SearchService.java",
                Map.of(SearchSignals.LEXICAL, 1.0d, SearchSignals.PATH, 0.5d));
        SearchCandidate second = candidate(
                "file:docs/search.md",
                "docs/search.md",
                Map.of(SearchSignals.LEXICAL, 0.7d));

        RankingRequest request = new RankingRequest("search", 5, true);
        List<RankedCandidate> historical = new DeterministicContextRanker().rank(request, List.of(first, second));
        List<RankedCandidate> hybrid = new SemanticHybridContextRanker().rank(request, List.of(first, second));

        assertEquals(historical, hybrid);
    }

    @Test
    void candidateSupportedByBothChannelsBeatsSingleChannelCandidates() {
        SearchCandidate baselineOnly = candidate(
                "file:src/Baseline.java",
                "src/Baseline.java",
                Map.of(SearchSignals.LEXICAL, 1.0d));
        SearchCandidate semanticOnly = candidate(
                "file:docs/semantic.md",
                "docs/semantic.md",
                Map.of(SearchSignals.SEMANTIC, 1.0d));
        SearchCandidate supportedByBoth = candidate(
                "file:docs/hybrid.md",
                "docs/hybrid.md",
                Map.of(
                        SearchSignals.LEXICAL, 0.8d,
                        SearchSignals.SEMANTIC, 0.9d));

        List<RankedCandidate> ranked = new SemanticHybridContextRanker().rank(
                new RankingRequest("hybrid retrieval", 3, true),
                List.of(baselineOnly, semanticOnly, supportedByBoth));

        assertEquals("file:docs/hybrid.md", ranked.getFirst().candidate().id());
        assertTrue(ranked.getFirst().components().containsKey("baselineRrfScore"));
        assertTrue(ranked.getFirst().components().containsKey("semanticRrfScore"));
        assertTrue(ranked.getFirst().reasons().stream().anyMatch(reason -> reason.contains("fusion RRF baseline")));
        assertTrue(ranked.getFirst().reasons().stream().anyMatch(reason -> reason.contains("fusion RRF sémantique")));
    }

    @Test
    void semanticOnlyCandidatesKeepTheirSemanticOrderDeterministically() {
        SearchCandidate semanticFirst = candidate(
                "file:docs/first.md",
                "docs/first.md",
                Map.of(SearchSignals.SEMANTIC, 0.90d));
        SearchCandidate semanticSecond = candidate(
                "file:docs/second.md",
                "docs/second.md",
                Map.of(SearchSignals.SEMANTIC, 0.80d));

        List<RankedCandidate> ranked = new SemanticHybridContextRanker().rank(
                new RankingRequest("paraphrased need", 2, false),
                List.of(semanticSecond, semanticFirst));

        assertEquals("file:docs/first.md", ranked.get(0).candidate().id());
        assertEquals("file:docs/second.md", ranked.get(1).candidate().id());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    private static SearchCandidate candidate(String id, String path, Map<String, Double> signals) {
        return new SearchCandidate(
                id,
                CandidateType.DOCUMENTATION,
                Path.of(path),
                null,
                path,
                signals);
    }
}
