package com.nexus.mcp;

import com.nexus.application.NexusApplication;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextItem;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.FederatedSearchHit;
import com.nexus.security.PublicProjectPathPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Responsabilité interne de la frontière MCP. */
final class McpResultMapper {
    Map<String, Object> search(NexusApplication.SearchOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project(operation.project()));
        result.put("query", operation.query());
        result.put("limit", operation.limit());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("results", operation.results().stream()
                .map(candidate -> rankedCandidate(operation.project(), candidate))
                .toList());
        return result;
    }

    Map<String, Object> federatedSearch(NexusApplication.FederatedSearchOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projects", operation.projects().stream().map(this::project).toList());
        result.put("query", operation.query());
        result.put("limit", operation.limit());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("results", operation.results().stream().map(this::federatedSearchHit).toList());
        return result;
    }

    Map<String, Object> federatedSearchHit(FederatedSearchHit hit) {
        return Map.of(
                "project", project(hit.project()),
                "result", rankedCandidate(hit.project(), hit.rankedCandidate()));
    }

    Map<String, Object> project(ProjectDescriptor project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.id().toString());
        result.put("name", project.name());
        result.put("sourceType", project.sourceType().name());
        result.put("languages", project.languages());
        result.put("technologies", project.technologies());
        result.put("lastIndexedAt", project.lastIndexedAt() == null ? null : project.lastIndexedAt().toString());
        result.put("indexStatus", project.indexStatus().name());
        return result;
    }

    Map<String, Object> rankedCandidate(ProjectDescriptor project, RankedCandidate ranked) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ranked.candidate().id());
        result.put("type", ranked.candidate().type().name());
        result.put("path", relativePath(project, ranked.candidate().path()));
        result.put("excerpt", com.nexus.security.SensitiveContentRedactor.redact(ranked.candidate().excerpt()));
        result.put("score", ranked.score());
        result.put("scoreComponents", ranked.components());
        result.put("reasons", new com.nexus.security.PublicDiagnosticPolicy(List.of(project.rootPath())).texts(ranked.reasons()));
        if (ranked.candidate().symbol() != null) {
            result.put("symbol", symbol(ranked.candidate().symbol()));
        }
        return result;
    }

    private static String relativePath(ProjectDescriptor project, java.nio.file.Path path) {
        return PublicProjectPathPolicy.expose(project.rootPath(), path);
    }

    Map<String, Object> indexedSymbol(IndexedSymbol indexed) {
        return Map.of("path", indexed.relativePath(), "symbol", symbol(indexed.symbol()));
    }

    Map<String, Object> symbol(com.nexus.index.CodeSymbol symbol) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", symbol.kind().name());
        result.put("name", symbol.name());
        result.put("qualifiedName", symbol.qualifiedName());
        result.put("signature", symbol.signature());
        result.put("startLine", symbol.startLine());
        result.put("endLine", symbol.endLine());
        result.put("sourceProvider", symbol.sourceProvider());
        return result;
    }

    Map<String, Object> relation(SymbolRelation relation) {
        return Map.of(
                "kind", relation.kind().name(),
                "source", relation.source(),
                "target", relation.target(),
                "confidence", relation.confidence(),
                "sourceProvider", relation.sourceProvider());
    }

    Map<String, Object> context(NexusApplication.ContextOperation operation) {
        var bundle = com.nexus.security.PublicContextPolicy.expose(operation.project(), operation.bundle(), operation.query());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project(operation.project()));
        result.put("query", operation.query());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("tokenBudget", bundle.tokenBudget());
        result.put("estimatedTokens", bundle.estimatedTokens());
        result.put("items", bundle.items().stream()
                .map(item -> contextItem(operation.project(), item))
                .toList());
        result.put("excluded", bundle.excluded());
        result.put("metadata", bundle.metadata());
        return result;
    }

    Map<String, Object> federatedContext(NexusApplication.FederatedContextOperation operation) {
        var bundle = com.nexus.security.PublicContextPolicy.expose(operation.projects(), operation.bundle(), operation.query());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projects", operation.projects().stream().map(this::project).toList());
        result.put("query", operation.query());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("tokenBudget", bundle.tokenBudget());
        result.put("estimatedTokens", bundle.estimatedTokens());
        result.put("items", bundle.items().stream().map(this::federatedContextItem).toList());
        result.put("excluded", bundle.excluded());
        result.put("metadata", bundle.metadata());
        return result;
    }

    Map<String, Object> federatedContextItem(FederatedContextItem federated) {
        return Map.of(
                "project", project(federated.project()),
                "item", contextItem(federated.project(), federated.item()));
    }

    Map<String, Object> contextItem(ProjectDescriptor project, ContextItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", item.type().name());
        result.put("path", relativePath(project, item.path()));
        result.put("symbol", item.symbol());
        result.put("startLine", item.startLine());
        result.put("endLine", item.endLine());
        result.put("content", item.content());
        result.put("score", item.score());
        result.put("scoreComponents", item.scoreComponents());
        result.put("reasons", item.reasons());
        result.put("estimatedTokens", item.estimatedTokens());
        result.put("truncated", item.truncated());
        return result;
    }

}
