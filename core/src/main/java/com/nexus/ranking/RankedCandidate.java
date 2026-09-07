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
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite: " + score);
        }
        components.forEach((component, value) -> {
            Objects.requireNonNull(component, "component name");
            Objects.requireNonNull(value, "component value");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Ranking component '" + component + "' must be finite: " + value);
            }
        });
        components = Map.copyOf(components);
        reasons = List.copyOf(reasons);
    }
}
