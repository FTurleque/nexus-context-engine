package com.nexus.search.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchQualityMetricsTest {

    @Test
    void computesPrecisionAndRecallAtK() {
        List<String> ranked = List.of("A", "B", "C", "D");
        Set<String> relevant = Set.of("A", "C", "X");

        assertEquals(2.0d / 3.0d, SearchQualityMetrics.precisionAtK(ranked, relevant, 3), 0.000001d);
        assertEquals(2.0d / 3.0d, SearchQualityMetrics.recallAtK(ranked, relevant, 3), 0.000001d);
    }

    @Test
    void computesReciprocalRankFromTheFirstRelevantUniqueResult() {
        List<String> ranked = List.of("noise", "noise", "relevant", "other");

        assertEquals(0.5d, SearchQualityMetrics.reciprocalRank(ranked, Set.of("relevant")), 0.000001d);
        assertEquals(0.0d, SearchQualityMetrics.reciprocalRank(ranked, Set.of("missing")), 0.000001d);
        assertEquals(1.0d, SearchQualityMetrics.reciprocalRank(ranked, Set.of()), 0.000001d);
    }

    @Test
    void computesBinaryNdcgAtK() {
        List<String> ranked = List.of("noise", "A", "B", "other");
        Set<String> relevant = Set.of("A", "B");
        double expected = (1.0d / log2(3.0d) + 1.0d / log2(4.0d))
                / (1.0d + 1.0d / log2(3.0d));

        assertEquals(expected, SearchQualityMetrics.ndcgAtK(ranked, relevant, 3), 0.000001d);
        assertEquals(1.0d, SearchQualityMetrics.ndcgAtK(ranked, Set.of(), 3), 0.000001d);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }
}
