package com.nexus.search;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchCandidateValidationTest {

    @Test
    void acceptsFiniteSignals() {
        assertDoesNotThrow(() -> candidate(Map.of(
                SearchSignals.LEXICAL, 0.75d,
                SearchSignals.GRAPH, -0.0d)));
    }

    @Test
    void rejectsNaNAndInfiniteSignalsAtTheDomainBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(Map.of(SearchSignals.LEXICAL, Double.NaN)));
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(Map.of(SearchSignals.LEXICAL, Double.POSITIVE_INFINITY)));
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(Map.of(SearchSignals.LEXICAL, Double.NEGATIVE_INFINITY)));
    }

    private static SearchCandidate candidate(Map<String, Double> signals) {
        return new SearchCandidate(
                "file:src/Main.java",
                CandidateType.FILE,
                Path.of("src/Main.java"),
                null,
                "class Main {}",
                signals);
    }
}
