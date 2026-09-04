package com.nexus.ranking;

import com.nexus.search.SearchCandidate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RankedCandidate(
        SearchCandidate candidate,
        double score,
        Map<String, Double> components,
        List<String> reasons) {

    public RankedCandidate {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(reasons, "reasons");
        components = Map.copyOf(components);
        reasons = List.copyOf(reasons);
    }
}
