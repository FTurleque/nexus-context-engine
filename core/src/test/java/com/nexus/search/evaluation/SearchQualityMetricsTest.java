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
}
