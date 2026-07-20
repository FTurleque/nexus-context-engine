package com.nexus.ranking;

import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DeterministicContextRanker implements ContextRanker {

    public static final double DEFAULT_GIT_RECENCY_WEIGHT = 0.05d;

    private static final Map<String, Double> BASE_WEIGHTS = Map.of(
            SearchSignals.LEXICAL, 0.40d,
            SearchSignals.SYMBOL_EXACT, 0.30d,
            SearchSignals.SYMBOL_FUZZY, 0.10d,
            SearchSignals.PATH, 0.10d,
            SearchSignals.GRAPH, 0.10d);

    private final double gitRecencyWeight;

    public DeterministicContextRanker() {
        this(DEFAULT_GIT_RECENCY_WEIGHT);
    }

    public DeterministicContextRanker(double gitRecencyWeight) {
        if (gitRecencyWeight < 0.0d || gitRecencyWeight > 0.20d) {
            throw new IllegalArgumentException("gitRecencyWeight must be between 0.0 and 0.20");
        }
        this.gitRecencyWeight = gitRecencyWeight;
    }

    @Override
    public List<RankedCandidate> rank(RankingRequest request, List<SearchCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> rankCandidate(request, candidate))
                .filter(candidate -> candidate.score() > 0.0d)
                .sorted(Comparator
                        .comparingDouble(RankedCandidate::score).reversed()
                        .thenComparingInt(candidate -> typeOrder(candidate.candidate().type()))
                        .thenComparing(candidate -> candidate.candidate().path().toString())
                        .thenComparing(candidate -> candidate.candidate().id()))
                .limit(request.limit())
                .toList();
    }

    private RankedCandidate rankCandidate(RankingRequest request, SearchCandidate candidate) {
        Map<String, Double> components = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        double score = 0.0d;

        for (String signal : List.of(
                SearchSignals.LEXICAL,
                SearchSignals.SYMBOL_EXACT,
                SearchSignals.SYMBOL_FUZZY,
                SearchSignals.PATH,
                SearchSignals.GRAPH,
                SearchSignals.GIT_RECENCY)) {
            double normalized = clamp(candidate.signals().getOrDefault(signal, 0.0d));
            double contribution = normalized * weight(signal);
            if (contribution <= 0.0d) {
                continue;
            }
            components.put(signal, contribution);
            score += contribution;
            if (request.explain()) {
                reasons.add(reason(signal, normalized, contribution));
            }
        }

        return new RankedCandidate(candidate, score, components, reasons);
    }

    private double weight(String signal) {
        if (SearchSignals.GIT_RECENCY.equals(signal)) {
            return gitRecencyWeight;
        }
        return BASE_WEIGHTS.getOrDefault(signal, 0.0d);
    }

    private static String reason(String signal, double normalized, double contribution) {
        String label = switch (signal) {
            case SearchSignals.LEXICAL -> "correspondance lexicale BM25";
            case SearchSignals.SYMBOL_EXACT -> "correspondance exacte de symbole";
            case SearchSignals.SYMBOL_FUZZY -> "similarité de symbole";
            case SearchSignals.PATH -> "correspondance du chemin";
            case SearchSignals.GRAPH -> "proximité dans le graphe";
            case SearchSignals.GIT_RECENCY -> "récence Git locale";
            default -> signal;
        };
        return String.format(Locale.ROOT, "%s: %.3f -> +%.3f", label, normalized, contribution);
    }

    private static int typeOrder(CandidateType type) {
        return switch (type) {
            case SYMBOL -> 0;
            case TEST -> 1;
            case FILE -> 2;
            case DOCUMENTATION -> 3;
            case INSTRUCTION -> 4;
            case SKILL -> 5;
            case GIT -> 6;
        };
    }

    private static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
