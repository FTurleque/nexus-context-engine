package com.nexus.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.context.ContextBudgetPolicy;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.ResultLimitPolicy;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.nexus.mcp.McpToolArguments.*;
import static com.nexus.mcp.McpToolSchemas.*;
import static com.nexus.mcp.McpProjectResolver.MAX_PROJECT_SELECTORS;

final class NexusMcpTools {

    private static final String REQUESTED_SOURCES_ARGUMENT = "requestedSources";
    private static final int DEFAULT_LIMIT = ResultLimitPolicy.DEFAULT_RESULT_LIMIT;
    private static final int DEFAULT_TOKEN_BUDGET = ContextBudgetPolicy.DEFAULT_CONTEXT_TOKEN_BUDGET;

    private final NexusApplication application;
    private final McpProjectResolver projects;
    private final McpResultMapper results = new McpResultMapper();
    private final ObjectMapper objectMapper;

    NexusMcpTools(NexusApplication application, ObjectMapper objectMapper) {
        this.application = Objects.requireNonNull(application, "application");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.projects = new McpProjectResolver(application);
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        return List.of(
                listProjects(),
                searchCode(),
                searchAcrossProjects(),
                findSymbol(),
                findUsages(),
                buildContext(false),
                buildContext(true),
                buildContextAcrossProjects(false),
                buildContextAcrossProjects(true));
    }

    private McpServerFeatures.SyncToolSpecification listProjects() {
        return tool(
                "list_projects",
                "Liste les projets enregistrés dans NEXUS avec leur état d'indexation.",
                objectSchema(Map.of(), List.of()),
                arguments -> application.listProjects().stream().map(results::project).toList());
    }

    private McpServerFeatures.SyncToolSpecification searchCode() {
        return tool(
                "search_code",
                "Recherche dans un projet READY avec le moteur hybride NEXUS.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "query", stringProperty("Requête de recherche"),
                                "limit", integerProperty("Nombre maximal de résultats, 10 par défaut", ResultLimitPolicy.MAX_RESULT_LIMIT),
                                "explain", booleanProperty("Inclure les explications de ranking")),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = projects.resolveProject(arguments);
                    NexusApplication.SearchOperation operation = application.search(
                            project.id(),
                            requiredString(arguments, "query"),
                            positiveInteger(arguments, "limit", DEFAULT_LIMIT, ResultLimitPolicy.MAX_RESULT_LIMIT),
                            booleanValue(arguments, "explain", false));
                    return results.search(operation);
                });
    }

    private McpServerFeatures.SyncToolSpecification searchAcrossProjects() {
        return tool(
                "search_across_projects",
                "Recherche fédérée NEXUS sur une portée explicite de projets READY, avec diversification globale par chemin.",
                objectSchema(
                        Map.of(
                                "projects", arrayOfStringsProperty(
                                        "UUID ou noms uniques des projets NEXUS",
                                        MAX_PROJECT_SELECTORS),
                                "query", stringProperty("Requête de recherche"),
                                "limit", integerProperty("Top-K global, 10 par défaut", ResultLimitPolicy.MAX_RESULT_LIMIT),
                                "explain", booleanProperty("Inclure les explications de ranking")),
                        List.of("projects", "query")),
                arguments -> {
                    List<ProjectDescriptor> projects = this.projects.resolveProjects(arguments);
                    NexusApplication.FederatedSearchOperation operation = application.searchAcrossProjects(
                            projects.stream().map(ProjectDescriptor::id).toList(),
                            requiredString(arguments, "query"),
                            positiveInteger(arguments, "limit", DEFAULT_LIMIT, ResultLimitPolicy.MAX_RESULT_LIMIT),
                            booleanValue(arguments, "explain", false));
                    return results.federatedSearch(operation);
                });
    }

    private McpServerFeatures.SyncToolSpecification findSymbol() {
        return tool(
                "find_symbol",
                "Recherche bornée de symboles indexés par nom ou nom qualifié dans un projet READY.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "query", stringProperty("Nom ou fragment de nom du symbole"),
                                "limit", integerProperty("Nombre maximal de symboles, 10 par défaut", ResultLimitPolicy.MAX_RESULT_LIMIT)),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = projects.resolveProject(arguments);
                    String query = requiredString(arguments, "query");
                    List<IndexedSymbol> symbols = application.findSymbols(
                            project.id(), query,
                            positiveInteger(arguments, "limit", DEFAULT_LIMIT, ResultLimitPolicy.MAX_RESULT_LIMIT));
                    return Map.of(
                            "project", results.project(project),
                            "query", query,
                            "symbols", symbols.stream().map(results::indexedSymbol).toList());
                });
    }

    private McpServerFeatures.SyncToolSpecification findUsages() {
        return tool(
                "find_usages",
                "Retourne les relations structurelles bornées portant sur le symbole demandé dans un projet READY.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "symbol", stringProperty("Nom ou nom qualifié du symbole"),
                                "limit", integerProperty("Nombre maximal de relations, 20 par défaut", ResultLimitPolicy.MAX_RESULT_LIMIT)),
                        List.of("project", "symbol")),
                arguments -> {
                    ProjectDescriptor project = projects.resolveProject(arguments);
                    String symbol = requiredString(arguments, "symbol");
                    List<SymbolRelation> relations = application.findUsages(
                            project.id(), symbol,
                            positiveInteger(arguments, "limit", 20, ResultLimitPolicy.MAX_RESULT_LIMIT));
                    return Map.of(
                            "project", results.project(project),
                            "symbol", symbol,
                            "relations", relations.stream().map(results::relation).toList());
                });
    }

    private McpServerFeatures.SyncToolSpecification buildContext(boolean forceExplain) {
        String name = forceExplain ? "explain_context" : "build_context";
        return tool(
                name,
                forceExplain
                        ? "Construit un ContextBundle projet-local et retourne les explications de sélection/exclusion."
                        : "Construit un ContextBundle NEXUS projet-local sous budget strict.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "query", stringProperty("Tâche ou demande de contexte"),
                                "tokenBudget", integerProperty("Budget maximal, 2000 par défaut", ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                                REQUESTED_SOURCES_ARGUMENT, arrayOfStringsProperty(
                                        "Sources optionnelles NEXUS",
                                        MAX_REQUESTED_SOURCES),
                                "constraints", objectProperty("Contraintes clé/valeur optionnelles")),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = projects.resolveProject(arguments);
                    NexusApplication.ContextOperation operation = application.context(
                            project.id(),
                            requiredString(arguments, "query"),
                            positiveInteger(
                                    arguments, "tokenBudget", DEFAULT_TOKEN_BUDGET,
                                    ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                            requestedSources(arguments.get(REQUESTED_SOURCES_ARGUMENT)),
                            stringMap(arguments.get("constraints")),
                            forceExplain);
                    return results.context(operation);
                });
    }

    private McpServerFeatures.SyncToolSpecification buildContextAcrossProjects(boolean forceExplain) {
        String name = forceExplain ? "explain_context_across_projects" : "build_context_across_projects";
        return tool(
                name,
                forceExplain
                        ? "Construit un contexte fédéré avec budget global, provenance projet, équité et explications."
                        : "Construit un contexte fédéré avec budget global, provenance projet et sources natives isolées par projet.",
                objectSchema(
                        Map.of(
                                "projects", arrayOfStringsProperty(
                                        "UUID ou noms uniques des projets NEXUS",
                                        MAX_PROJECT_SELECTORS),
                                "query", stringProperty("Tâche ou demande de contexte"),
                                "tokenBudget", integerProperty("Budget global maximal, 2000 par défaut", ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                                REQUESTED_SOURCES_ARGUMENT, arrayOfStringsProperty(
                                        "Sources optionnelles NEXUS",
                                        MAX_REQUESTED_SOURCES),
                                "constraints", objectProperty("Contraintes clé/valeur optionnelles")),
                        List.of("projects", "query")),
                arguments -> {
                    List<ProjectDescriptor> projects = this.projects.resolveProjects(arguments);
                    NexusApplication.FederatedContextOperation operation = application.contextAcrossProjects(
                            projects.stream().map(ProjectDescriptor::id).toList(),
                            requiredString(arguments, "query"),
                            positiveInteger(
                                    arguments, "tokenBudget", DEFAULT_TOKEN_BUDGET,
                                    ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                            requestedSources(arguments.get(REQUESTED_SOURCES_ARGUMENT)),
                            stringMap(arguments.get("constraints")),
                            forceExplain);
                    return results.federatedContext(operation);
                });
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> schema,
            ToolHandler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, schema).description(description).build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> arguments = request.arguments();
                        if (arguments == null) arguments = Map.of();
                        if (schema.get("properties") instanceof Map<?, ?> properties
                                && !properties.keySet().containsAll(arguments.keySet())) {
                            throw new IllegalArgumentException("Propriété JSON inconnue");
                        }
                        return textResult(handler.handle(arguments), false);
                    } catch (Exception exception) {
                        return textResult(Map.of(
                                "error", "nexus_tool_error",
                                "message", safeMessage(exception)), true);
                    }
                })
                .build();
    }

    McpSchema.CallToolResult textResult(Object value, boolean error) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.builder(json).build()))
                    .isError(error)
                    .build();
        } catch (JsonProcessingException exception) {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.builder(
                            "{\"error\":\"serialization_error\",\"message\":\"Impossible de sérialiser la réponse NEXUS\"}").build()))
                    .isError(true)
                    .build();
        }
    }

    static String safeMessage(Exception exception) {
        Objects.requireNonNull(exception, "exception");
        if (exception instanceof IllegalArgumentException) {
            String message = exception.getMessage();
            return message == null || message.isBlank()
                    ? "Requête MCP NEXUS invalide"
                    : com.nexus.security.PublicDiagnosticPolicy.internal().text(message);
        }
        if (exception instanceof IllegalStateException) {
            return "Opération NEXUS indisponible dans l'état courant";
        }
        return "Erreur interne NEXUS";
    }

    @FunctionalInterface
    private interface ToolHandler {
        Object handle(Map<String, Object> arguments) throws Exception;
    }
}
