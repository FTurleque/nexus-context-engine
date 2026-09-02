package com.nexus.context;

import com.nexus.security.SensitiveContentRedactor;

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
        items = items.stream()
                .map(ContextBundle::redact)
                .toList();
        excluded = List.copyOf(excluded);
        metadata = Map.copyOf(metadata);
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }
        if (estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("estimatedTokens must be between zero and tokenBudget");
        }
    }

    private static ContextItem redact(ContextItem item) {
        ContextItem nonNull = Objects.requireNonNull(item, "item");
        String redactedContent = SensitiveContentRedactor.redact(nonNull.content());
        if (redactedContent.equals(nonNull.content())) {
            return nonNull;
        }
        return new ContextItem(
                nonNull.type(),
                nonNull.path(),
                nonNull.symbol(),
                nonNull.startLine(),
                nonNull.endLine(),
                redactedContent,
                nonNull.score(),
                nonNull.scoreComponents(),
                nonNull.reasons(),
                nonNull.estimatedTokens(),
                nonNull.truncated());
    }
}
