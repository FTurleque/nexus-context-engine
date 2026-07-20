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

class DeterministicContextRankerGitTest {

    @Test
    void addsAnExplainableGitRecencyBonus() {
        SearchCandidate candidate = new SearchCandidate(
                "file:Service.java",
                CandidateType.FILE,
                Path.of("Service.java"),
                null,
                "Service.java",
                Map.of(
                        SearchSignals.LEXICAL, 0.5d,
                        SearchSignals.GIT_RECENCY, 1.0d));

        RankedCandidate ranked = new DeterministicContextRanker(0.05d)
                .rank(new RankingRequest("service", 1, true), List.of(candidate))
                .getFirst();

        assertEquals(0.25d, ranked.score(), 0.000001d);
        assertEquals(0.05d, ranked.components().get(SearchSignals.GIT_RECENCY), 0.000001d);
        assertTrue(ranked.reasons().stream().anyMatch(reason -> reason.contains("récence Git locale")));
    }

    @Test
    void canDisableGitRecencyWithoutChangingLegacyScore() {
        SearchCandidate candidate = new SearchCandidate(
                "file:Service.java",
                CandidateType.FILE,
                Path.of("Service.java"),
                null,
                "Service.java",
                Map.of(
                        SearchSignals.LEXICAL, 0.5d,
                        SearchSignals.GIT_RECENCY, 1.0d));

        RankedCandidate ranked = new DeterministicContextRanker(0.0d)
                .rank(new RankingRequest("service", 1, true), List.of(candidate))
                .getFirst();

        assertEquals(0.20d, ranked.score(), 0.000001d);
        assertTrue(!ranked.components().containsKey(SearchSignals.GIT_RECENCY));
    }
}
