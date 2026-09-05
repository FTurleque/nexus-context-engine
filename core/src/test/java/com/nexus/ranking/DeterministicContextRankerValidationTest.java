package com.nexus.ranking;

import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicContextRankerValidationTest {

    @Test
    void acceptsFiniteBoundaryWeights() {
        assertDoesNotThrow(() -> new DeterministicContextRanker(0.0d, 0.0d));
        assertDoesNotThrow(() -> new DeterministicContextRanker(0.20d, 1.0d));
    }

    @Test
    void rejectsNonFiniteWeights() {
        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DeterministicContextRanker(invalid, 0.15d));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DeterministicContextRanker(0.05d, invalid));
        }
    }

    @Test
    void rankedCandidateRejectsNonFiniteScoreAndComponents() {
        SearchCandidate candidate = new SearchCandidate(
                "candidate",
                CandidateType.FILE,
                Path.of("src/Main.java"),
                null,
                "excerpt",
                Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> new RankedCandidate(candidate, Double.NaN, Map.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RankedCandidate(
                        candidate,
                        0.5d,
                        Map.of("lexical", Double.POSITIVE_INFINITY),
                        List.of()));
    }
}
