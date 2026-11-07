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

    public static double reciprocalRank(List<String> rankedIds, Set<String> relevantIds) {
        Objects.requireNonNull(rankedIds, "rankedIds");
        Objects.requireNonNull(relevantIds, "relevantIds");
        if (relevantIds.isEmpty()) {
            return 1.0d;
        }
        Set<String> unique = new HashSet<>();
        int rank = 0;
        for (String id : rankedIds) {
            if (!unique.add(id)) {
                continue;
            }
            rank++;
            if (relevantIds.contains(id)) {
                return 1.0d / rank;
            }
        }
        return 0.0d;
    }

    public static double ndcgAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
        validate(rankedIds, relevantIds, k);
        if (relevantIds.isEmpty()) {
            return 1.0d;
        }

        Set<String> unique = new HashSet<>();
        double dcg = 0.0d;
        int rank = 0;
        for (String id : rankedIds) {
            if (!unique.add(id)) {
                continue;
            }
            rank++;
            if (rank > k) {
                break;
            }
            if (relevantIds.contains(id)) {
                dcg += discount(rank);
            }
        }

        int idealRelevant = Math.min(k, relevantIds.size());
        double idealDcg = 0.0d;
        for (int idealRank = 1; idealRank <= idealRelevant; idealRank++) {
            idealDcg += discount(idealRank);
        }
        return idealDcg == 0.0d ? 1.0d : dcg / idealDcg;
    }

    private static double discount(int rank) {
        return 1.0d / (Math.log(rank + 1.0d) / Math.log(2.0d));
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
