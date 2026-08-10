package com.nexus.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.context.ContextBudgetPolicy;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextItem;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.ResultLimitPolicy;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class NexusMcpTools {

    private static final int DEFAULT_LIMIT = ResultLimitPolicy.DEFAULT_RESULT_LIMIT;
    private static final int DEFAULT_TOKEN_BUDGET = ContextBudgetPolicy.DEFAULT_CONTEXT_TOKEN_BUDGET;

    private final NexusApplication application;
    private final ObjectMapper objectMapper;

    NexusMcpTools(NexusApplication application, ObjectMapper objectMapper) {
        this.application = Objects.requireNonNull(application, "application");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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
                arguments -> application.listProjects().stream().map(this::project).toList());
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
                    ProjectDescriptor project = resolveProject(arguments);
                    NexusApplication.SearchOperation operation = application.search(
                            project.id(),
                            requiredString(arguments, "query"),
                            positiveInteger(arguments, "limit", DEFAULT_LIMIT, ResultLimitPolicy.MAX_RESULT_LIMIT),
                            booleanValue(arguments, "explain", false));
                    return search(operation);
                });
    }

    private McpServerFeatures.SyncToolSpecification searchAcrossProjects() {
        return tool(
                "search_across_projects",
                "Recherche fédérée NEXUS sur une portée explicite de projets READY, avec diversification globale par chemin.",
                objectSchema(
                        Map.of(
                                "projects", arrayOfStringsProperty("UUID ou noms uniques des projets NEXUS"),
                                "query", stringProperty("Requête de recherche"),
                                "limit", integerProperty("Top-K global, 10 par défaut", ResultLimitPolicy.MAX_RESULT_LIMIT),
                                "explain", booleanProperty("Inclure les explications de ranking")),
                        List.of("projects", "query")),
                arguments -> {
                    List<ProjectDescriptor> projects = resolveProjects(arguments);
                    NexusApplication.FederatedSearchOperation operation = application.searchAcrossProjects(
                            projects.stream().map(ProjectDescriptor::id).toList(),
                            requiredString(arguments, "query"),
                            positiveInteger(arguments, "limit", DEFAULT_LIMIT, ResultLimitPolicy.MAX_RESULT_LIMIT),
                            booleanValue(arguments, "explain", false));
                    return federatedSearch(operation);
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
                    ProjectDescriptor project = resolveProject(arguments);
                    String query = requiredString(arguments, "query");
                    List<IndexedSymbol> symbols = application.findSymbols(
                            project.id(), query,
                            positiveInteger(arguments, "limit", DEFAULT_LIMIT, ResultLimitPolicy.MAX_RESULT_LIMIT));
                    return Map.of(
                            "project", project(project),
                            "query", query,
                            "symbols", symbols.stream().map(this::indexedSymbol).toList());
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
                    ProjectDescriptor project = resolveProject(arguments);
                    String symbol = requiredString(arguments, "symbol");
                    List<SymbolRelation> relations = application.findUsages(
                            project.id(), symbol,
                            positiveInteger(arguments, "limit", 20, ResultLimitPolicy.MAX_RESULT_LIMIT));
                    return Map.of(
                            "project", project(project),
                            "symbol", symbol,
                            "relations", relations.stream().map(this::relation).toList());
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
                                "requestedSources", arrayOfStringsProperty("Sources optionnelles NEXUS"),
                                "constraints", objectProperty("Contraintes clé/valeur optionnelles")),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = resolveProject(arguments);
                    NexusApplication.ContextOperation operation = application.context(
                            project.id(),
                            requiredString(arguments, "query"),
                            positiveInteger(
                                    arguments, "tokenBudget", DEFAULT_TOKEN_BUDGET,
                                    ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                            requestedSources(arguments.get("requestedSources")),
                            stringMap(arguments.get("constraints")),
                            forceExplain);
                    return context(operation);
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
                                "projects", arrayOfStringsProperty("UUID ou noms uniques des projets NEXUS"),
                                "query", stringProperty("Tâche ou demande de contexte"),
                                "tokenBudget", integerProperty("Budget global maximal, 2000 par défaut", ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                                "requestedSources", arrayOfStringsProperty("Sources optionnelles NEXUS"),
                                "constraints", objectProperty("Contraintes clé/valeur optionnelles")),
                        List.of("projects", "query")),
                arguments -> {
                    List<ProjectDescriptor> projects = resolveProjects(arguments);
                    NexusApplication.FederatedContextOperation operation = application.contextAcrossProjects(
                            projects.stream().map(ProjectDescriptor::id).toList(),
                            requiredString(arguments, "query"),
                            positiveInteger(
                                    arguments, "tokenBudget", DEFAULT_TOKEN_BUDGET,
                                    ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET),
                            requestedSources(arguments.get("requestedSources")),
                            stringMap(arguments.get("constraints")),
                            forceExplain);
                    return federatedContext(operation);
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
                        return textResult(handler.handle(request.arguments()), false);
                    } catch (Exception exception) {
                        return textResult(Map.of(
                                "error", "nexus_tool_error",
                                "message", safeMessage(exception)), true);
                    }
                })
                .build();
    }

    private McpSchema.CallToolResult textResult(Object value, boolean error) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(json)))
                    .isError(error)
                    .build();
        } catch (JsonProcessingException exception) {
            return McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent(
                            "{\"error\":\"serialization_error\",\"message\":\"Impossible de sérialiser la réponse NEXUS\"}")))
                    .isError(true)
                    .build();
        }
    }

    private ProjectDescriptor resolveProject(Map<String, Object> arguments) {
        return application.resolveProject(requiredString(arguments, "project"));
    }

    private List<ProjectDescriptor> resolveProjects(Map<String, Object> arguments) {
        Object value = arguments.get("projects");
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("projects doit être un tableau non vide");
        }

        Map<String, String> uniqueSelectors = new LinkedHashMap<>();
        for (Object rawSelector : values) {
            String selector = String.valueOf(rawSelector).trim();
            uniqueSelectors.putIfAbsent(selector.toLowerCase(Locale.ROOT), selector);
        }
        List<String> selectors = List.copyOf(uniqueSelectors.values());

        // Les UUID explicites permettent de prouver un dépassement avant le
        // moindre accès repository. Les noms/alias restent résolus normalement.
        FederatedScopePolicy.validateExplicitUuidSelectors(selectors);

        List<ProjectDescriptor> projects = new ArrayList<>();
        Set<UUID> seen = new java.util.LinkedHashSet<>();
        for (String selector : selectors) {
            ProjectDescriptor project = application.resolveProject(selector);
            if (seen.add(project.id())) {
                FederatedScopePolicy.validateUniqueCount(seen.size());
                projects.add(project);
            }
        }
        return List.copyOf(projects);
    }

    private Map<String, Object> search(NexusApplication.SearchOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project(operation.project()));
        result.put("query", operation.query());
        result.put("limit", operation.limit());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("results", operation.results().stream().map(this::rankedCandidate).toList());
        return result;
    }

    private Map<String, Object> federatedSearch(NexusApplication.FederatedSearchOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projects", operation.projects().stream().map(this::project).toList());
        result.put("query", operation.query());
        result.put("limit", operation.limit());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("results", operation.results().stream().map(this::federatedSearchHit).toList());
        return result;
    }

    private Map<String, Object> federatedSearchHit(FederatedSearchHit hit) {
        return Map.of(
                "project", project(hit.project()),
                "result", rankedCandidate(hit.rankedCandidate()));
    }

    private Map<String, Object> project(ProjectDescriptor project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.id().toString());
        result.put("name", project.name());
        result.put("rootPath", project.rootPath().toString());
        result.put("sourceType", project.sourceType().name());
        result.put("languages", project.languages());
        result.put("technologies", project.technologies());
        result.put("lastIndexedAt", project.lastIndexedAt() == null ? null : project.lastIndexedAt().toString());
        result.put("indexStatus", project.indexStatus().name());
        return result;
    }

    private Map<String, Object> rankedCandidate(RankedCandidate ranked) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ranked.candidate().id());
        result.put("type", ranked.candidate().type().name());
        result.put("path", ranked.candidate().path().toString());
        result.put("excerpt", ranked.candidate().excerpt());
        result.put("score", ranked.score());
        result.put("scoreComponents", ranked.components());
        result.put("reasons", ranked.reasons());
        if (ranked.candidate().symbol() != null) {
            result.put("symbol", symbol(ranked.candidate().symbol()));
        }
        return result;
    }

    private Map<String, Object> indexedSymbol(IndexedSymbol indexed) {
        return Map.of("path", indexed.relativePath(), "symbol", symbol(indexed.symbol()));
    }

    private Map<String, Object> symbol(com.nexus.index.CodeSymbol symbol) {
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

    private Map<String, Object> relation(SymbolRelation relation) {
        return Map.of(
                "kind", relation.kind().name(),
                "source", relation.source(),
                "target", relation.target(),
                "confidence", relation.confidence(),
                "sourceProvider", relation.sourceProvider());
    }

    private Map<String, Object> context(NexusApplication.ContextOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project", project(operation.project()));
        result.put("query", operation.query());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("tokenBudget", operation.bundle().tokenBudget());
        result.put("estimatedTokens", operation.bundle().estimatedTokens());
        result.put("items", operation.bundle().items().stream().map(this::contextItem).toList());
        result.put("excluded", operation.bundle().excluded());
        result.put("metadata", operation.bundle().metadata());
        return result;
    }

    private Map<String, Object> federatedContext(NexusApplication.FederatedContextOperation operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projects", operation.projects().stream().map(this::project).toList());
        result.put("query", operation.query());
        result.put("explain", operation.explain());
        result.put("durationMs", operation.durationMs());
        result.put("tokenBudget", operation.bundle().tokenBudget());
        result.put("estimatedTokens", operation.bundle().estimatedTokens());
        result.put("items", operation.bundle().items().stream().map(this::federatedContextItem).toList());
        result.put("excluded", operation.bundle().excluded());
        result.put("metadata", operation.bundle().metadata());
        return result;
    }

    private Map<String, Object> federatedContextItem(FederatedContextItem federated) {
        return Map.of(
                "project", project(federated.project()),
                "item", contextItem(federated.item()));
    }

    private Map<String, Object> contextItem(ContextItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", item.type().name());
        result.put("path", item.path().toString());
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

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description, int maximum) {
        return Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", maximum,
                "description", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> arrayOfStringsProperty(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    private static Map<String, Object> objectProperty(String description) {
        return Map.of(
                "type", "object",
                "additionalProperties", Map.of("type", "string"),
                "description", description);
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " est obligatoire");
        }
        return string.trim();
    }

    private static int positiveInteger(
            Map<String, Object> arguments,
            String name,
            int defaultValue,
            int maximum) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = new BigDecimal(value.toString()).toBigIntegerExact().intValueExact();
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " doit être strictement positif");
            }
            if (parsed > maximum) {
                throw new IllegalArgumentException(name + " doit être inférieur ou égal à " + maximum);
            }
            return parsed;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(name + " doit être un entier dans les bornes autorisées", exception);
        }
    }

    private static boolean booleanValue(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments.get(name);
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private static Set<CandidateType> requestedSources(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("requestedSources doit être un tableau");
        }
        return values.stream()
                .map(Object::toString)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> {
                    try {
                        return CandidateType.valueOf(item.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Source de contexte inconnue : " + item, exception);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> stringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("constraints doit être un objet");
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    private static String safeMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface ToolHandler {
        Object handle(Map<String, Object> arguments) throws Exception;
    }
}
