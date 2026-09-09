package com.nexus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NexusMcpNegativeContractTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test void invalidArgumentsReturnPublicErrorsAndNeverPartialResults() throws Exception {
        try (var app = NexusApplication.create(new NexusPaths(temporary.resolve("home")))) {
            var ready = app.registerProject(Files.createDirectory(temporary.resolve("ready")), "ready");
            app.index(ready.id(), true, false);
            app.registerProject(Files.createDirectory(temporary.resolve("pending")), "pending");
            var tools = new NexusMcpTools(app, json);
            for (Map<String, Object> args : List.<Map<String, Object>>of(
                    Map.of(), Map.of("project", "ready"), Map.of("query", "x"),
                    Map.of("project", "ready", "query", "x", "unknown", true),
                    Map.of("project", "pending", "query", "x"),
                    Map.of("project", UUID.randomUUID().toString(), "query", "x"),
                    Map.of("project", "invalid-uuid-0000", "query", "x"),
                    Map.of("project", "ready", "query", "x", "explain", "maybe"))) {
                assertPublicError(call(tools, "search_code", args));
            }
            for (Object limit : List.of(0, -1, 100_001, 1.5, "garbage", true)) {
                assertPublicError(call(tools, "search_code", Map.of("project", "ready", "query", "x", "limit", limit)));
            }
            for (Object budget : List.of(0, -1, Integer.MAX_VALUE, 0.5, "NaN", true)) {
                assertPublicError(call(tools, "build_context", Map.of("project", "ready", "query", "x", "tokenBudget", budget)));
            }
            for (Object scope : List.of(List.of(), List.of("ready", "missing"), List.of("ready", "pending"),
                    List.of("ready", 42), "ready")) {
                assertPublicError(call(tools, "search_across_projects", Map.of("projects", scope, "query", "x")));
            }
            assertPublicError(call(tools, "build_context", Map.of("project", "ready", "query", "x",
                    "requestedSources", List.of("UNKNOWN"))));
            assertPublicError(call(tools, "build_context", Map.of("project", "ready", "query", "x",
                    "constraints", Map.of("unsupported", "value"))));
            var empty = call(tools, "search_code", Map.of("project", "ready", "query", "absent", "limit", 1));
            assertFalse(Boolean.TRUE.equals(empty.isError()));
            assertEquals(0, body(empty).path("results").size());
        }
    }

    @Test void internalAndProviderErrorsAreGenericEvenWhenTheCauseContainsSensitivePaths() {
        for (Exception failure : List.of(new java.io.IOException("/tmp/Build Secret/cache"),
                new IllegalStateException("C:\\Users\\Alice Smith\\secret"))) {
            String message = NexusMcpTools.safeMessage(failure);
            assertFalse(message.contains("Build Secret"));
            assertFalse(message.contains("Alice Smith"));
            assertFalse(message.contains("cache"));
        }
        assertEquals("[INTERNAL_PATH]", NexusMcpTools.safeMessage(new IllegalArgumentException("invalid: /tmp/Build Secret/cache")));
    }


    @Test void finalContextEnvelopeRedactsProviderMetadataAndSourceSecrets() throws Exception {
        try (var app = NexusApplication.create(new NexusPaths(temporary.resolve("home")))) {
            var project = app.registerProject(Files.createDirectory(temporary.resolve("project")), "privacy");
            var tools = new NexusMcpTools(app, json);
            var paths = List.of("/home/alice/My Project/src/App.java", "/tmp/Build Secret/cache/file.txt",
                    "/var/lib/nexus/private data/index", "C:\\Users\\Alice Smith\\Nexus Project\\src\\App.java",
                    "D:\\Private Build\\cache\\data.bin", "\\\\server\\Private Share\\Nexus Project\\file.java",
                    "file:///home/alice/My%20Project/src/App.java", "file:///C:/Users/Alice%20Smith/Nexus/file.java");
            var item = new com.nexus.context.ContextItem(com.nexus.search.CandidateType.FILE, Path.of("src/App.java"),
                    null, 1, 1, "password=12345678", 1, Map.of(), paths, 10, false);
            var bundle = new com.nexus.context.ContextBundle(List.of(item), 100, 10, paths,
                    Map.of("nestedProvider", List.of(Map.of("diagnostic", paths))));
            var operation = new NexusApplication.ContextOperation(project, "query", true, 0, bundle);
            var envelope = tools.textResult(tools.context(operation), false);
            String payload = json.writeValueAsString(envelope);
            assertFalse(Boolean.TRUE.equals(envelope.isError()));
            for (String secret : List.of("alice", "Alice", "Build Secret", "Private", "cache", "file.java", "file:", "12345678")) {
                assertFalse(payload.contains(secret), payload);
            }
            assertTrue(payload.contains("src/App.java"));
            var unknown = new com.nexus.context.ContextBundle(List.of(), 100, 0, List.of(), Map.of("future", new Object()));
            assertThrows(IllegalStateException.class, () -> tools.context(
                    new NexusApplication.ContextOperation(project, "query", true, 0, unknown)));
        }
    }

    @Test void internalSerializationFailureProducesAGenericPublicEnvelope() throws Exception {
        try (var app = NexusApplication.create(new NexusPaths(temporary.resolve("error-home")))) {
            var brokenMapper = new ObjectMapper() {
                private boolean first = true;
                @Override public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                    if (first) {
                        first = false;
                        throw new IllegalStateException("/tmp/Build Secret/cache");
                    }
                    return super.writeValueAsString(value);
                }
            };
            var result = call(new NexusMcpTools(app, brokenMapper), "list_projects", Map.of());
            assertPublicError(result);
            assertFalse(json.writeValueAsString(result).contains("Build Secret"));
        }
    }

    private McpSchema.CallToolResult call(NexusMcpTools tools, String name, Map<String, Object> args) {
        var specification = tools.specifications().stream().filter(s -> s.tool().name().equals(name)).findFirst().orElseThrow();
        return specification.callHandler().apply(null, McpSchema.CallToolRequest.builder(name).arguments(args).build());
    }

    private com.fasterxml.jackson.databind.JsonNode body(McpSchema.CallToolResult result) throws Exception {
        return json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
    }

    private void assertPublicError(McpSchema.CallToolResult result) throws Exception {
        assertTrue(Boolean.TRUE.equals(result.isError()));
        var body = body(result);
        assertEquals("nexus_tool_error", body.path("error").asText());
        assertFalse(body.path("message").asText().isBlank());
        assertFalse(body.has("results"));
        assertFalse(json.writeValueAsString(result).contains("stackTrace"));
    }
}
