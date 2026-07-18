package io.github.fturleque.nexus.ranking;

import io.github.fturleque.nexus.context.ContextRequest;
import io.github.fturleque.nexus.search.SearchCandidate;

import java.util.List;

public interface ContextRanker {

    List<RankedCandidate> rank(ContextRequest request, List<SearchCandidate> candidates);
}
