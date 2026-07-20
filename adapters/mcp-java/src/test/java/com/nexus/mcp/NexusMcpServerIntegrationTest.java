package com.nexus.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusMcpServerIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesTheSameSearchAndContextResultsThroughARealMcpStdioClient() throws Exception {
        Path nexusHome = temporaryDirectory.resolve("nexus-home");
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve("src/main/java/demo/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                public class OrderService {
                    public String processOrder(String id) {
                        return "processed-" + id;
                    }
                }
                """);

        NexusApplication application = NexusApplication.create(new NexusPaths(nexusHome));
        var project = application.registerProject(projectRoot, "mcp-validation");
        application.index(project.id(), true, false);

        var directSearch = application.search(project.id(), "OrderService process order", 5, true);
        var directContext = application.context(
                project.id(),
                "OrderService process order",
                200,
                Set.of(),
                Map.of(),
                false);

        ServerParameters parameters = ServerParameters.builder(javaExecutable())
                .args(
                        "-Dnexus.home=" + nexusHome.toAbsolutePath(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        NexusMcpServer.class.getName())
                .build();

        try (McpSyncClient client = McpClient.sync(
                        new StdioClientTransport(parameters, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {
            client.initialize();

            var tools = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertTrue(tools.contains("search_code"));
            assertTrue(tools.contains("find_symbol"));
            assertTrue(tools.contains("find_usages"));
            assertTrue(tools.contains("build_context"));
            assertTrue(tools.contains("explain_context"));

            McpSchema.CallToolResult searchResult = client.callTool(
                    McpSchema.CallToolRequest.builder("search_code")
                            .arguments(Map.of(
                                    "project", project.id().toString(),
                                    "query", "OrderService process order",
                                    "limit", 5,
                                    "explain", true))
                            .build());
            assertFalse(Boolean.TRUE.equals(searchResult.isError()));
            JsonNode searchJson = json(searchResult);
            assertEquals(
                    directSearch.results().getFirst().candidate().path().toString(),
                    searchJson.path("results").get(0).path("path").asText());
            assertEquals(
                    directSearch.results().getFirst().score(),
                    searchJson.path("results").get(0).path("score").asDouble(),
                    0.0000001d);

            McpSchema.CallToolResult contextResult = client.callTool(
                    McpSchema.CallToolRequest.builder("build_context")
                            .arguments(Map.of(
                                    "project", "mcp-validation",
                                    "query", "OrderService process order",
                                    "tokenBudget", 200))
                            .build());
            assertFalse(Boolean.TRUE.equals(contextResult.isError()));
            JsonNode contextJson = json(contextResult);
            assertEquals(directContext.bundle().tokenBudget(), contextJson.path("tokenBudget").asInt());
            assertEquals(directContext.bundle().estimatedTokens(), contextJson.path("estimatedTokens").asInt());
            assertEquals(
                    directContext.bundle().items().getFirst().path().toString(),
                    contextJson.path("items").get(0).path("path").asText());

            McpSchema.CallToolResult symbolResult = client.callTool(
                    McpSchema.CallToolRequest.builder("find_symbol")
                            .arguments(Map.of(
                                    "project", project.id().toString(),
                                    "query", "OrderService"))
                            .build());
            assertFalse(Boolean.TRUE.equals(symbolResult.isError()));
            assertTrue(json(symbolResult).path("symbols").size() > 0);
        }
    }

    private JsonNode json(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().getFirst();
        return new ObjectMapper().readTree(content.text());
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
