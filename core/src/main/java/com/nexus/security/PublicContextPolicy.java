package com.nexus.security;

import com.nexus.paths.RepositoryPath;

import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextItem;
import com.nexus.project.ProjectDescriptor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Défense commune REST/MCP/CLI, appliquée même aux bundles de producteurs tiers. */
public final class PublicContextPolicy {
    private PublicContextPolicy() { }

    public static ContextBundle expose(ProjectDescriptor project, ContextBundle bundle, String clientQuery) {
        var diagnostics = new PublicDiagnosticPolicy(List.of(project.rootPath()));
        return new ContextBundle(bundle.items().stream().map(item -> item(project, item)).toList(),
                bundle.tokenBudget(), bundle.estimatedTokens(), diagnostics.texts(bundle.excluded()),
                metadata(diagnostics, bundle.metadata(), clientQuery));
    }

    public static FederatedContextBundle expose(List<ProjectDescriptor> projects,
            FederatedContextBundle bundle, String clientQuery) {
        var diagnostics = new PublicDiagnosticPolicy(projects.stream().map(ProjectDescriptor::rootPath).toList());
        return new FederatedContextBundle(bundle.items().stream()
                .map(value -> new FederatedContextItem(value.project(), item(value.project(), value.item()))).toList(),
                bundle.tokenBudget(), bundle.estimatedTokens(), diagnostics.texts(bundle.excluded()),
                metadata(diagnostics, bundle.metadata(), clientQuery));
    }

    private static Map<String, Object> metadata(PublicDiagnosticPolicy policy,
            Map<String, Object> metadata, String clientQuery) {
        Map<String, Object> internal = new LinkedHashMap<>(metadata);
        boolean hasQuery = internal.containsKey("query");
        internal.remove("query");
        Map<String, Object> safe = new LinkedHashMap<>(policy.metadata(internal));
        if (hasQuery) safe.put("query", clientQuery);
        return Map.copyOf(safe);
    }

    private static ContextItem item(ProjectDescriptor project, ContextItem item) {
        var diagnostics = new PublicDiagnosticPolicy(List.of(project.rootPath()));
        return new ContextItem(item.type(), new RepositoryPath(PublicProjectPathPolicy.expose(project.rootPath(), item.path()))
                .toPath(project.rootPath().getFileSystem()),
                diagnostics.text(item.symbol()), item.startLine(), item.endLine(),
                SensitiveContentRedactor.redact(item.content()), item.score(), item.scoreComponents(),
                diagnostics.texts(item.reasons()), item.estimatedTokens(), item.truncated());
    }
}
