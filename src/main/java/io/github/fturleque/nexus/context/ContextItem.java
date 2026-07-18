package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.search.CandidateType;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record ContextItem(
        CandidateType type,
        Path path,
        String symbol,
        String content,
        double score,
        List<String> reasons,
        int estimatedTokens) {

    public ContextItem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
    }
}
