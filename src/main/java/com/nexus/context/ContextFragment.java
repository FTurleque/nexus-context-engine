package com.nexus.context;

import com.nexus.search.CandidateType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fragment intermédiaire matérialisé depuis un candidat classé, avant
 * déduplication et sélection sous budget.
 */
public record ContextFragment(
        CandidateType type,
        Path path,
        String symbol,
        int startLine,
        int endLine,
        String content,
        double score,
        Map<String, Double> scoreComponents,
        List<String> reasons) {

    public ContextFragment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(scoreComponents, "scoreComponents");
        Objects.requireNonNull(reasons, "reasons");
        scoreComponents = Map.copyOf(scoreComponents);
        reasons = List.copyOf(reasons);
        if (startLine <= 0 || endLine < startLine) {
            throw new IllegalArgumentException("invalid fragment line range");
        }
    }
}
