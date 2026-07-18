package io.github.fturleque.nexus.ranking;

import io.github.fturleque.nexus.search.SearchCandidate;

import java.util.List;
import java.util.Objects;

public record RankedCandidate(SearchCandidate candidate, double score, List<String> reasons) {

    public RankedCandidate {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
    }
}
