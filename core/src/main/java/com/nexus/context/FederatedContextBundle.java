package com.nexus.context;

import com.nexus.project.ProjectDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ContextBundle multi-projet respectant un budget global unique.
 *
 * <p>{@code unservedProjects} nomme, dans l'ordre du scope, les projets que
 * l'échéance globale a empêché de commencer (BE-0064) ; elle est vide sans coupe.</p>
 */
public record FederatedContextBundle(
        List<FederatedContextItem> items,
        int tokenBudget,
        int estimatedTokens,
        List<String> excluded,
        Map<String, Object> metadata,
        List<ProjectDescriptor> unservedProjects) {

    public FederatedContextBundle(
            List<FederatedContextItem> items,
            int tokenBudget,
            int estimatedTokens,
            List<String> excluded,
            Map<String, Object> metadata) {
        this(items, tokenBudget, estimatedTokens, excluded, metadata, List.of());
    }

    public FederatedContextBundle {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(excluded, "excluded");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(unservedProjects, "unservedProjects");
        items = List.copyOf(items);
        unservedProjects = List.copyOf(unservedProjects);
        var diagnostics = new com.nexus.security.PublicDiagnosticPolicy(
                items.stream().map(item -> item.project().rootPath()).distinct().toList());
        excluded = diagnostics.texts(excluded);
        metadata = diagnostics.metadata(metadata);
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }
        if (estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("estimatedTokens must be between zero and tokenBudget");
        }
    }
}
