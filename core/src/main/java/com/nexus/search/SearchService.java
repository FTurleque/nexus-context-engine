package com.nexus.search;

import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.ContextRanker;
import com.nexus.ranking.RankedCandidate;
import com.nexus.ranking.RankingRequest;
import com.nexus.ranking.graph.GraphCandidateEnricher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SearchService {

    private final List<SearchStrategy> strategies;
    private final CandidateMerger candidateMerger;
    private final List<CandidateEnricher> enrichers;
    private final ContextRanker ranker;

    public SearchService(
            List<SearchStrategy> strategies,
            GraphCandidateEnricher graphEnricher,
            ContextRanker ranker) {
        this(strategies, List.of(graphEnricher), ranker);
    }

    public SearchService(
            List<SearchStrategy> strategies,
            List<CandidateEnricher> enrichers,
            ContextRanker ranker) {
        this.strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        this.enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers"));
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.candidateMerger = new CandidateMerger();
    }

    public List<RankedCandidate> search(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain) throws IOException {
        Objects.requireNonNull(project, "project");
        String normalizedQuery = QueryPolicy.normalize(query);
        ResultLimitPolicy.validate(limit);

        int retrievalLimit = Math.clamp((long) limit * 3, 20, ResultLimitPolicy.MAX_RESULT_LIMIT);
        List<SearchCandidate> rawCandidates = new ArrayList<>();
        for (SearchStrategy strategy : strategies) {
            rawCandidates.addAll(strategy.search(project, normalizedQuery, retrievalLimit));
        }

        List<SearchCandidate> enriched = candidateMerger.merge(rawCandidates);
        for (CandidateEnricher enricher : enrichers) {
            enriched = enricher.enrich(project, enriched);
        }
        return ranker.rank(new RankingRequest(normalizedQuery, limit, explain), enriched);
    }
}
