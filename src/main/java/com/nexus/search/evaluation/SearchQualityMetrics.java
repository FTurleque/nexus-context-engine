package com.nexus.search.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SearchQualityMetrics {

    private SearchQualityMetrics() {
    }

    public static double precisionAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
        validate(rankedIds, relevantIds, k);
        int relevant = relevantInTopK(rankedIds, relevantIds, k);
        return (double) relevant / k;
    }

    public static double recallAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
        validate(rankedIds, relevantIds, k);
        if (relevantIds.isEmpty()) {
            return 1.0d;
        }
        int relevant = relevantInTopK(rankedIds, relevantIds, k);
        return (double) relevant / relevantIds.size();
    }

    private static int relevantInTopK(List<String> rankedIds, Set<String> relevantIds, int k) {
        Set<String> unique = new HashSet<>();
        int matches = 0;
        for (String id : rankedIds.stream().limit(k).toList()) {
            if (unique.add(id) && relevantIds.contains(id)) {
                matches++;
            }
        }
        return matches;
    }

    private static void validate(List<String> rankedIds, Set<String> relevantIds, int k) {
        Objects.requireNonNull(rankedIds, "rankedIds");
        Objects.requireNonNull(relevantIds, "relevantIds");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be greater than zero");
        }
    }
}
