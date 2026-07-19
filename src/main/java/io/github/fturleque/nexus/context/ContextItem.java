package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.search.CandidateType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Élément effectivement retenu dans un {@link ContextBundle}.
 *
 * <p>Les lignes sont exprimées en base 1. Une valeur de {@code 0} pour
 * {@code startLine}/{@code endLine} est réservée aux futures sources qui ne
 * correspondent pas à une plage de fichier.</p>
 */
public record ContextItem(
        CandidateType type,
        Path path,
        String symbol,
        int startLine,
        int endLine,
        String content,
        double score,
        Map<String, Double> scoreComponents,
        List<String> reasons,
        int estimatedTokens,
        boolean truncated) {

    public ContextItem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(scoreComponents, "scoreComponents");
        Objects.requireNonNull(reasons, "reasons");
        scoreComponents = Map.copyOf(scoreComponents);
        reasons = List.copyOf(reasons);
        if (startLine < 0 || endLine < 0) {
            throw new IllegalArgumentException("line numbers must not be negative");
        }
        if ((startLine == 0) != (endLine == 0)) {
            throw new IllegalArgumentException("startLine and endLine must both be zero or both be positive");
        }
        if (startLine > 0 && endLine < startLine) {
            throw new IllegalArgumentException("endLine must be greater than or equal to startLine");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
    }
}
