package com.nexus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.exposure.PublicOutput;
import com.nexus.exposure.PublicOutputSchema;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parité des outils MCP avec le contrat de {@link PublicOutputSchema}, sur la même fixture que
 * {@code CliOutputParityTest} et {@code NexusRestOutputParityTest}. MCP n'a aucune clé d'enveloppe ;
 * {@code find_symbol} et {@code find_usages} sont ses seuls outils sans équivalent CLI/REST.
 */
class NexusMcpOutputParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QUERY = "OrderService process order";

    @TempDir
    Path temporary;

    private NexusApplication application;
    private NexusMcpTools tools;

    @BeforeEach
    void indexFixture() throws Exception {
        application = NexusApplication.create(new NexusPaths(temporary.resolve("home")));
        for (String name : List.of("first", "second")) {
            Path root = Files.createDirectories(temporary.resolve(name).resolve("src/main/java/demo"));
            Files.writeString(root.resolve("OrderService.java"), """
                    package demo;

                    public class OrderService {
                        public String process(String order) {
                            return order;
                        }
                    }
                    """);
            var project = application.registerProject(temporary.resolve(name), name);
            application.index(project.id(), true, false);
        }
        tools = new NexusMcpTools(application, JSON);
    }

    @AfterEach
    void close() throws Exception {
        application.close();
    }

    @Test
    void searchCodeMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = call("search_code", Map.of("project", "first", "query", QUERY, "explain", true));
        assertFalse(((List<?>) payload.get("results")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Search.class, payload, List.of()));
    }

    @Test
    void searchAcrossProjectsMatchesThePublicContract() throws Exception {
        Map<?, ?> payload = call("search_across_projects",
                Map.of("projects", List.of("first", "second"), "query", QUERY, "explain", true));
        assertFalse(((List<?>) payload.get("results")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.FederatedSearch.class, payload, List.of()));
    }

    @Test
    void contextToolsMatchThePublicContract() throws Exception {
        for (String tool : List.of("build_context", "explain_context")) {
            Map<?, ?> payload = call(tool, Map.of("project", "first", "query", QUERY));
            assertFalse(((List<?>) payload.get("items")).isEmpty(), tool);
            assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Context.class, payload, List.of()), tool);
        }
    }

    @Test
    void federatedContextToolsMatchThePublicContract() throws Exception {
        for (String tool : List.of("build_context_across_projects", "explain_context_across_projects")) {
            Map<?, ?> payload = call(tool, Map.of("projects", List.of("first", "second"), "query", QUERY));
            assertFalse(((List<?>) payload.get("items")).isEmpty(), tool);
            assertEquals(List.of(),
                    PublicOutputSchema.divergences(PublicOutput.FederatedContext.class, payload, List.of()), tool);
        }
    }

    @Test
    void symbolToolsAreTheDeclaredMcpOnlyOperationsAndMatchTheContract() throws Exception {
        assertEquals(List.of("find_symbol", "find_usages"), PublicOutputSchema.MCP_ONLY_OPERATIONS);
        Map<?, ?> symbols = call("find_symbol", Map.of("project", "first", "query", "OrderService"));
        assertFalse(((List<?>) symbols.get("symbols")).isEmpty());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.SymbolSearch.class, symbols, List.of()));
        Map<?, ?> usages = call("find_usages", Map.of("project", "first", "symbol", "OrderService"));
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Usages.class, usages, List.of()));
        assertTrue(tools.specifications().stream().map(spec -> spec.tool().name()).toList()
                .containsAll(PublicOutputSchema.MCP_ONLY_OPERATIONS));
    }

    @Test
    void listProjectsMatchesThePublicContract() throws Exception {
        String json = text(invoke("list_projects", Map.of()));
        for (Object project : JSON.readValue(json, List.class)) {
            assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Project.class, project, List.of()));
        }
    }

    private Map<?, ?> call(String tool, Map<String, Object> arguments) throws Exception {
        return JSON.readValue(text(invoke(tool, arguments)), Map.class);
    }

    private McpSchema.CallToolResult invoke(String tool, Map<String, Object> arguments) {
        McpServerFeatures.SyncToolSpecification specification = tools.specifications().stream()
                .filter(spec -> spec.tool().name().equals(tool))
                .findFirst()
                .orElseThrow();
        McpSchema.CallToolResult result = specification.callHandler()
                .apply(null, new McpSchema.CallToolRequest(tool, arguments));
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> text(result));
        return result;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }
}
