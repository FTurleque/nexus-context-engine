package com.nexus.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contexte multi-projet sélectionné sous un budget de tokens global.
 */
public record FederatedContextBundle(
        List<FederatedContextItem> items,
        int tokenBudget,
        int estimatedTokens,
        List<String> excluded,
        Map<String, Object> metadata) {

    public FederatedContextBundle {
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
