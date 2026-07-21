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

class SemanticRankingTest {

    @Test
    void semanticSignalContributesOnlyWhenPresent() {
        SearchCandidate semanticCandidate = new SearchCandidate(
                "file:docs/architecture.md",
                CandidateType.DOCUMENTATION,
                Path.of("docs", "architecture.md"),
                null,
                "architecture",
                Map.of(SearchSignals.SEMANTIC, 0.80d));

        RankedCandidate ranked = new DeterministicContextRanker()
                .rank(new RankingRequest("domain boundaries", 5, true), List.of(semanticCandidate))
                .getFirst();

        assertEquals(0.12d, ranked.score(), 0.000001d);
        assertEquals(0.12d, ranked.components().get(SearchSignals.SEMANTIC), 0.000001d);
        assertTrue(ranked.reasons().stream().anyMatch(reason -> reason.contains("similarité sémantique")));
    }

    @Test
    void historicalCandidateWithoutSemanticSignalKeepsHistoricalScore() {
        SearchCandidate lexicalCandidate = new SearchCandidate(
                "file:src/main/java/com/nexus/search/SearchService.java",
                CandidateType.FILE,
                Path.of("src", "main", "java", "com", "nexus", "search", "SearchService.java"),
                null,
                "SearchService",
                Map.of(
                        SearchSignals.LEXICAL, 0.75d,
                        SearchSignals.PATH, 0.50d));

        RankedCandidate ranked = new DeterministicContextRanker()
                .rank(new RankingRequest("SearchService", 5, false), List.of(lexicalCandidate))
                .getFirst();

        assertEquals(0.35d, ranked.score(), 0.000001d);
        assertTrue(ranked.components().containsKey(SearchSignals.LEXICAL));
        assertTrue(ranked.components().containsKey(SearchSignals.PATH));
    }
}
