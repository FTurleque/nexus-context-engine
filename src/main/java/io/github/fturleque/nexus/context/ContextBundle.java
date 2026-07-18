package io.github.fturleque.nexus.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContextBundle(
        List<ContextItem> items,
        int tokenBudget,
        int estimatedTokens,
        List<String> excluded,
        Map<String, Object> metadata) {

    public ContextBundle {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(excluded, "excluded");
        Objects.requireNonNull(metadata, "metadata");
        items = List.copyOf(items);
        excluded = List.copyOf(excluded);
        metadata = Map.copyOf(metadata);
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }
        if (estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("estimatedTokens must be between zero and tokenBudget");
        }
    }
}
