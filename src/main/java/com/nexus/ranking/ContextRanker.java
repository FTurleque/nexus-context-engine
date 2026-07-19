package com.nexus.ranking;

import com.nexus.search.SearchCandidate;

import java.util.List;

public interface ContextRanker {

    List<RankedCandidate> rank(RankingRequest request, List<SearchCandidate> candidates);
}
