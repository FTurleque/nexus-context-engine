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
        List<ContextItem> redacted = new java.util.ArrayList<>(items.size());
        List<String> exclusions = new java.util.ArrayList<>(excluded);
        for (ContextItem item : items) {
            try {
                redacted.add(redact(item));
            } catch (com.nexus.security.OversizedSecretException exception) {
                exclusions.add(com.nexus.security.OversizedSecretException.exclusion(item.path()));
            }
        }
        items = List.copyOf(redacted);
        excluded = com.nexus.security.PublicDiagnosticPolicy.internal().texts(exclusions);
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
        return new ContextItem(
                nonNull.type(),
                nonNull.path(),
                nonNull.symbol(),
                nonNull.startLine(),
                nonNull.endLine(),
                redactedContent,
                nonNull.score(),
                nonNull.scoreComponents(),
                com.nexus.security.PublicDiagnosticPolicy.internal().texts(nonNull.reasons()),
                nonNull.estimatedTokens(),
                nonNull.truncated());
    }
}
