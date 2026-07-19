package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.ranking.ContextRanker;
import io.github.fturleque.nexus.ranking.RankedCandidate;
import io.github.fturleque.nexus.ranking.RankingRequest;
import io.github.fturleque.nexus.ranking.graph.GraphCandidateEnricher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SearchService {

    private final List<SearchStrategy> strategies;
    private final CandidateMerger candidateMerger;
    private final GraphCandidateEnricher graphEnricher;
    private final ContextRanker ranker;

    public SearchService(
            List<SearchStrategy> strategies,
            GraphCandidateEnricher graphEnricher,
            ContextRanker ranker) {
        this.strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
        this.graphEnricher = Objects.requireNonNull(graphEnricher, "graphEnricher");
        this.ranker = Objects.requireNonNull(ranker, "ranker");
        this.candidateMerger = new CandidateMerger();
    }

    public List<RankedCandidate> search(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }

        int retrievalLimit = Math.min(500, Math.max(20, limit * 3));
        List<SearchCandidate> rawCandidates = new ArrayList<>();
        for (SearchStrategy strategy : strategies) {
            rawCandidates.addAll(strategy.search(project, query, retrievalLimit));
        }

        List<SearchCandidate> merged = candidateMerger.merge(rawCandidates);
        List<SearchCandidate> enriched = graphEnricher.enrich(project, merged);
        return ranker.rank(new RankingRequest(query, limit, explain), enriched);
    }
}
