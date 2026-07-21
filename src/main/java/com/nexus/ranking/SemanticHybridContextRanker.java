package com.nexus.ranking;

import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fusionne le ranking historique et le ranking sémantique par Reciprocal Rank Fusion.
 *
 * <p>La fusion travaille sur les rangs plutôt que sur les scores bruts afin de ne pas
 * mélanger directement BM25, similarité de symboles, graphe et cosine kNN, dont les
 * échelles ne sont pas comparables. Sans signal sémantique, le ranking historique est
 * délégué tel quel.</p>
 */
public final class SemanticHybridContextRanker implements ContextRanker {

    public static final int DEFAULT_RRF_K = 60;
    public static final String BASELINE_RRF_COMPONENT = "baselineRrfScore";
    public static final String SEMANTIC_RRF_COMPONENT = "semanticRrfScore";

    private final ContextRanker baselineRanker;
    private final int rrfK;

    public SemanticHybridContextRanker() {
        this(
                new DeterministicContextRanker(
                        DeterministicContextRanker.DEFAULT_GIT_RECENCY_WEIGHT,
                        0.0d),
                DEFAULT_RRF_K);
    }

    SemanticHybridContextRanker(ContextRanker baselineRanker, int rrfK) {
        this.baselineRanker = Objects.requireNonNull(baselineRanker, "baselineRanker");
        if (rrfK <= 0) {
            throw new IllegalArgumentException("rrfK must be greater than zero");
        }
        this.rrfK = rrfK;
    }

    @Override
    public List<RankedCandidate> rank(RankingRequest request, List<SearchCandidate> candidates) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");

        boolean hasSemanticSignal = candidates.stream()
                .anyMatch(candidate -> candidate.signals().getOrDefault(SearchSignals.SEMANTIC, 0.0d) > 0.0d);
        if (!hasSemanticSignal) {
            return baselineRanker.rank(request, candidates);
        }

        RankingRequest fullRequest = new RankingRequest(
                request.query(),
                Math.max(1, candidates.size()),
                request.explain());
        List<RankedCandidate> baselineRanking = baselineRanker.rank(fullRequest, candidates);

        Map<String, Integer> baselineRanks = ranksOfRankedCandidates(baselineRanking);
        Map<String, RankedCandidate> baselineById = new HashMap<>();
        for (RankedCandidate candidate : baselineRanking) {
            baselineById.put(candidate.candidate().id(), candidate);
        }

        List<SearchCandidate> semanticRanking = candidates.stream()
                .filter(candidate -> candidate.signals().getOrDefault(SearchSignals.SEMANTIC, 0.0d) > 0.0d)
                .sorted(Comparator
                        .comparingDouble((SearchCandidate candidate) ->
                                candidate.signals().getOrDefault(SearchSignals.SEMANTIC, 0.0d))
                        .reversed()
                        .thenComparing(candidate -> candidate.path().toString())
                        .thenComparing(SearchCandidate::id))
                .toList();
        Map<String, Integer> semanticRanks = ranksOfSearchCandidates(semanticRanking);

        double channelMaximum = reciprocalRank(1);
        double normalizer = channelMaximum * 2.0d;
        List<FusedCandidate> fused = new ArrayList<>(candidates.size());

        for (SearchCandidate candidate : candidates) {
            Integer baselineRank = baselineRanks.get(candidate.id());
            Integer semanticRank = semanticRanks.get(candidate.id());
            if (baselineRank == null && semanticRank == null) {
                continue;
            }

            double baselineContribution = baselineRank == null
                    ? 0.0d
                    : reciprocalRank(baselineRank) / normalizer;
            double semanticContribution = semanticRank == null
                    ? 0.0d
                    : reciprocalRank(semanticRank) / normalizer;
            double score = baselineContribution + semanticContribution;

            Map<String, Double> components = new LinkedHashMap<>();
            if (baselineContribution > 0.0d) {
                components.put(BASELINE_RRF_COMPONENT, baselineContribution);
            }
            if (semanticContribution > 0.0d) {
                components.put(SEMANTIC_RRF_COMPONENT, semanticContribution);
            }

            List<String> reasons = new ArrayList<>();
            if (request.explain()) {
                if (baselineRank != null) {
                    reasons.add("fusion RRF baseline: rang " + baselineRank
                            + " -> +" + format(baselineContribution));
                    RankedCandidate baseline = baselineById.get(candidate.id());
                    if (baseline != null) {
                        baseline.reasons().forEach(reason -> reasons.add("baseline: " + reason));
                    }
                }
                if (semanticRank != null) {
                    reasons.add("fusion RRF sémantique: rang " + semanticRank
                            + " -> +" + format(semanticContribution));
                }
            }

            fused.add(new FusedCandidate(
                    new RankedCandidate(candidate, score, components, reasons),
                    baselineRank == null ? Integer.MAX_VALUE : baselineRank,
                    semanticRank == null ? Integer.MAX_VALUE : semanticRank));
        }

        return fused.stream()
                .sorted(Comparator
                        .comparingDouble((FusedCandidate candidate) -> candidate.ranked().score()).reversed()
                        .thenComparingInt(FusedCandidate::baselineRank)
                        .thenComparingInt(FusedCandidate::semanticRank)
                        .thenComparing(candidate -> candidate.ranked().candidate().path().toString())
                        .thenComparing(candidate -> candidate.ranked().candidate().id()))
                .limit(request.limit())
                .map(FusedCandidate::ranked)
                .toList();
    }

    private double reciprocalRank(int rank) {
        return 1.0d / (rrfK + rank);
    }

    private static Map<String, Integer> ranksOfRankedCandidates(List<RankedCandidate> candidates) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            ranks.putIfAbsent(candidates.get(index).candidate().id(), index + 1);
        }
        return ranks;
    }

    private static Map<String, Integer> ranksOfSearchCandidates(List<SearchCandidate> candidates) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            ranks.putIfAbsent(candidates.get(index).id(), index + 1);
        }
        return ranks;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record FusedCandidate(RankedCandidate ranked, int baselineRank, int semanticRank) {
    }
}
