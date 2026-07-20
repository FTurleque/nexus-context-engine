package com.nexus.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.context.ContextItem;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

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

    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_TOKEN_BUDGET = 2_000;

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
                findSymbol(),
                findUsages(),
                buildContext(false),
                buildContext(true));
    }

    private McpServerFeatures.SyncToolSpecification listProjects() {
        return tool(
                "list_projects",
                "Liste les projets déjà enregistrés dans NEXUS afin de récupérer leur UUID ou leur nom avant d'appeler les autres outils.",
                objectSchema(Map.of(), List.of()),
                arguments -> application.listProjects().stream().map(this::project).toList());
    }

    private McpServerFeatures.SyncToolSpecification searchCode() {
        return tool(
                "search_code",
                "Recherche dans un projet NEXUS avec exactement le moteur hybride et le ranking utilisés par les autres adaptateurs. Retourne fichiers et symboles classés, avec explications optionnelles.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "query", stringProperty("Requête de recherche en langage naturel ou identifiant de code"),
                                "limit", integerProperty("Nombre maximal de résultats, 10 par défaut"),
                                "explain", booleanProperty("Inclure les composantes de score et raisons de classement")),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = resolveProject(arguments);
                    String query = requiredString(arguments, "query");
                    int limit = positiveInteger(arguments, "limit", DEFAULT_LIMIT);
                    boolean explain = booleanValue(arguments, "explain", false);
                    try {
                        NexusApplication.SearchOperation operation =
                                application.search(project.id(), query, limit, explain);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("project", project(operation.project()));
                        result.put("query", operation.query());
                        result.put("limit", operation.limit());
                        result.put("explain", operation.explain());
                        result.put("durationMs", operation.durationMs());
                        result.put("results", operation.results().stream().map(this::rankedCandidate).toList());
                        return result;
                    } catch (Exception exception) {
                        throw new IllegalStateException("La recherche NEXUS a échoué", exception);
                    }
                });
    }

    private McpServerFeatures.SyncToolSpecification findSymbol() {
        return tool(
                "find_symbol",
                "Recherche des symboles indexés par nom ou nom qualifié dans un projet NEXUS, sans recalculer le ranking métier.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "query", stringProperty("Nom ou fragment de nom du symbole"),
                                "limit", integerProperty("Nombre maximal de symboles, 10 par défaut")),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = resolveProject(arguments);
                    String query = requiredString(arguments, "query");
                    int limit = positiveInteger(arguments, "limit", DEFAULT_LIMIT);
                    List<IndexedSymbol> symbols = application.findSymbols(project.id(), query, limit);
                    return Map.of(
                            "project", project(project),
                            "query", query,
                            "symbols", symbols.stream().map(this::indexedSymbol).toList());
                });
    }

    private McpServerFeatures.SyncToolSpecification findUsages() {
        return tool(
                "find_usages",
                "Retourne les relations structurelles connues par NEXUS dont la source ou la cible correspond au symbole demandé. Les données peuvent provenir de JavaParser, SCIP ou d'un provider profond optionnel.",
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "symbol", stringProperty("Nom ou nom qualifié du symbole"),
                                "limit", integerProperty("Nombre maximal de relations, 20 par défaut")),
                        List.of("project", "symbol")),
                arguments -> {
                    ProjectDescriptor project = resolveProject(arguments);
                    String symbol = requiredString(arguments, "symbol");
                    int limit = positiveInteger(arguments, "limit", 20);
                    List<SymbolRelation> relations = application.findUsages(project.id(), symbol, limit);
                    return Map.of(
                            "project", project(project),
                            "symbol", symbol,
                            "relations", relations.stream().map(this::relation).toList());
                });
    }

    private McpServerFeatures.SyncToolSpecification buildContext(boolean forceExplain) {
        String name = forceExplain ? "explain_context" : "build_context";
        String description = forceExplain
                ? "Construit un ContextBundle NEXUS sous budget et retourne toutes les explications de sélection, troncature et exclusion disponibles."
                : "Construit le ContextBundle NEXUS pertinent pour une tâche en respectant strictement le budget de tokens configuré.";
        return tool(
                name,
                description,
                objectSchema(
                        Map.of(
                                "project", stringProperty("UUID ou nom unique du projet NEXUS"),
                                "query", stringProperty("Tâche ou demande pour laquelle construire le contexte"),
                                "tokenBudget", integerProperty("Budget maximal de tokens estimés, 2000 par défaut"),
                                "requestedSources", arrayOfStringsProperty("Sources optionnelles: FILE, SYMBOL, TEST, DOCUMENTATION, INSTRUCTION, SKILL, GIT"),
                                "constraints", objectProperty("Contraintes clé/valeur optionnelles transmises au ContextBuilder")),
                        List.of("project", "query")),
                arguments -> {
                    ProjectDescriptor project = resolveProject(arguments);
                    String query = requiredString(arguments, "query");
                    int tokenBudget = positiveInteger(arguments, "tokenBudget", DEFAULT_TOKEN_BUDGET);
                    Set<CandidateType> requestedSources = requestedSources(arguments.get("requestedSources"));
                    Map<String, String> constraints = stringMap(arguments.get("constraints"));
                    NexusApplication.ContextOperation operation = application.context(
                            project.id(),
                            query,
                            tokenBudget,
                            requestedSources,
                            constraints,
                            forceExplain);
                    return context(operation);
                });
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> schema,
            ToolHandler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, schema)
                .description(description)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Object result = handler.handle(request.arguments());
                        return textResult(result, false);
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
        return Map.of(
                "path", indexed.relativePath(),
                "symbol", symbol(indexed.symbol()));
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

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required) {
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

    private static Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "minimum", 1, "description", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> arrayOfStringsProperty(String description) {
        return Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", description);
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

    private static int positiveInteger(Map<String, Object> arguments, String name, int defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " doit être un entier", exception);
            }
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " doit être strictement positif");
        }
        return parsed;
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
