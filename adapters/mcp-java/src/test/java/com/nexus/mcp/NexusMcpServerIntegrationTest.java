package com.nexus.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.search.QueryPolicy;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusMcpServerIntegrationTest {

    private static final String CHILD_JACOCO_PROPERTY = "nexus.mcp.child.jacoco.argLine";
    private static final String CHILD_JACOCO_DEST_FILE_PROPERTY = "nexus.mcp.child.jacoco.destFile";
    private static final String NATIVE_ACCESS_ARGUMENT = "--enable-native-access=ALL-UNNAMED";
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "list_projects",
            "search_code",
            "search_across_projects",
            "find_symbol",
            "find_usages",
            "build_context",
            "explain_context",
            "build_context_across_projects",
            "explain_context_across_projects");

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

        ChildCoverage childCoverage = childCoverage();
        List<String> serverArguments = new ArrayList<>();
        serverArguments.add(NATIVE_ACCESS_ARGUMENT);
        if (childCoverage != null) {
            serverArguments.add(childCoverage.agentArgLine());
        }
        serverArguments.add("-Dnexus.home=" + nexusHome.toAbsolutePath());
        serverArguments.add("-cp");
        serverArguments.add(System.getProperty("java.class.path"));
        serverArguments.add(NexusMcpServer.class.getName());

        ServerParameters parameters = ServerParameters.builder(javaExecutable())
                .args(serverArguments.toArray(String[]::new))
                .build();

        McpSyncClient client = McpClient.sync(
                        new StdioClientTransport(parameters, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        try {
            client.initialize();

            var tools = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            assertEquals(EXPECTED_TOOLS.size(), tools.size());
            assertEquals(EXPECTED_TOOLS, Set.copyOf(tools));

            McpSchema.CallToolResult projectList = client.callTool(
                    McpSchema.CallToolRequest.builder("list_projects").arguments(Map.of()).build());
            assertFalse(Boolean.TRUE.equals(projectList.isError()));
            assertEquals(1, json(projectList).size());

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

            McpSchema.CallToolResult explainContext = client.callTool(
                    McpSchema.CallToolRequest.builder("explain_context")
                            .arguments(Map.of(
                                    "project", project.id().toString(),
                                    "query", "OrderService process order",
                                    "tokenBudget", 200))
                            .build());
            assertFalse(Boolean.TRUE.equals(explainContext.isError()));
            assertTrue(json(explainContext).path("explain").asBoolean());

            McpSchema.CallToolResult federatedContext = client.callTool(
                    McpSchema.CallToolRequest.builder("build_context_across_projects")
                            .arguments(Map.of(
                                    "projects", List.of(project.id().toString()),
                                    "query", "OrderService process order",
                                    "tokenBudget", 200))
                            .build());
            assertFalse(Boolean.TRUE.equals(federatedContext.isError()));
            assertEquals(1, json(federatedContext).path("projects").size());

            McpSchema.CallToolResult explainFederatedContext = client.callTool(
                    McpSchema.CallToolRequest.builder("explain_context_across_projects")
                            .arguments(Map.of(
                                    "projects", List.of("mcp-validation"),
                                    "query", "OrderService process order",
                                    "tokenBudget", 200))
                            .build());
            assertFalse(Boolean.TRUE.equals(explainFederatedContext.isError()));
            assertTrue(json(explainFederatedContext).path("explain").asBoolean());

            McpSchema.CallToolResult symbolResult = client.callTool(
                    McpSchema.CallToolRequest.builder("find_symbol")
                            .arguments(Map.of(
                                    "project", project.id().toString(),
                                    "query", "OrderService"))
                            .build());
            assertFalse(Boolean.TRUE.equals(symbolResult.isError()));
            assertTrue(json(symbolResult).path("symbols").size() > 0);

            McpSchema.CallToolResult usagesResult = client.callTool(
                    McpSchema.CallToolRequest.builder("find_usages")
                            .arguments(Map.of(
                                    "project", project.id().toString(),
                                    "symbol", "OrderService",
                                    "limit", 20))
                            .build());
            assertFalse(Boolean.TRUE.equals(usagesResult.isError()));
            assertTrue(json(usagesResult).path("relations").isArray());

            String oversizedUtf8 = "é".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES / 2 + 1);
            McpSchema.CallToolResult oversizedResult = client.callTool(
                    McpSchema.CallToolRequest.builder("search_code")
                            .arguments(Map.of(
                                    "project", project.id().toString(),
                                    "query", oversizedUtf8,
                                    "limit", 1))
                            .build());
            assertTrue(Boolean.TRUE.equals(oversizedResult.isError()));
            JsonNode oversizedJson = json(oversizedResult);
            assertEquals("nexus_tool_error", oversizedJson.path("error").asText());
            assertTrue(oversizedJson.path("message").asText().contains("octets UTF-8"));

            McpSchema.CallToolResult oversizedScope = client.callTool(
                    McpSchema.CallToolRequest.builder("search_across_projects")
                            .arguments(Map.of(
                                    "projects", uniqueProjectSelectors(FederatedScopePolicy.MAX_PROJECTS + 1),
                                    "query", "OrderService",
                                    "limit", 1))
                            .build());
            assertTrue(Boolean.TRUE.equals(oversizedScope.isError()));
            JsonNode oversizedScopeJson = json(oversizedScope);
            assertEquals("nexus_tool_error", oversizedScopeJson.path("error").asText());
            assertEquals(
                    FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE,
                    oversizedScopeJson.path("message").asText());

            List<String> duplicateHeavyScope = new ArrayList<>();
            for (int repetition = 0; repetition < FederatedScopePolicy.MAX_PROJECTS + 50; repetition++) {
                duplicateHeavyScope.add(project.id().toString());
            }
            McpSchema.CallToolResult duplicateScope = client.callTool(
                    McpSchema.CallToolRequest.builder("search_across_projects")
                            .arguments(Map.of(
                                    "projects", duplicateHeavyScope,
                                    "query", "OrderService",
                                    "limit", 1))
                            .build());
            assertFalse(Boolean.TRUE.equals(duplicateScope.isError()));
            assertEquals(1, json(duplicateScope).path("projects").size());

            if (childCoverage != null) {
                dumpChildCoverage(childCoverage);
            }
        } finally {
            assertTrue(client.closeGracefully(), "The MCP server process must stop before the test completes");
        }
    }

    private static ChildCoverage childCoverage() throws Exception {
        String configuredArgLine = System.getProperty(CHILD_JACOCO_PROPERTY);
        if (configuredArgLine == null || configuredArgLine.isBlank() || configuredArgLine.contains("${")) {
            return null;
        }

        String destination = System.getProperty(CHILD_JACOCO_DEST_FILE_PROPERTY);
        if (destination == null || destination.isBlank() || destination.contains("${")) {
            throw new IllegalStateException("Missing MCP child JaCoCo destination while the child agent is enabled");
        }

        int optionsSeparator = configuredArgLine.indexOf('=');
        String javaAgent = optionsSeparator < 0
                ? configuredArgLine.trim()
                : configuredArgLine.substring(0, optionsSeparator).trim();
        int port = freeLoopbackPort();
        String agentArgLine = javaAgent
                + "=output=tcpserver,address=" + LOOPBACK_ADDRESS
                + ",port=" + port
                + ",dumponexit=false";
        return new ChildCoverage(agentArgLine, Path.of(destination).toAbsolutePath(), port);
    }

    private static int freeLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_ADDRESS))) {
            return socket.getLocalPort();
        }
    }

    private static void dumpChildCoverage(ChildCoverage coverage) throws Exception {
        ExecDumpClient dumpClient = new ExecDumpClient();
        dumpClient.setRetryCount(20);
        dumpClient.setRetryDelay(100);
        ExecFileLoader executionData = dumpClient.dump(LOOPBACK_ADDRESS, coverage.port());
        assertFalse(
                executionData.getExecutionDataStore().getContents().isEmpty(),
                "The MCP child JVM must expose JaCoCo execution data before it is stopped");
        Files.deleteIfExists(coverage.destination());
        executionData.save(coverage.destination().toFile(), false);
        assertTrue(Files.size(coverage.destination()) > 0, "The MCP child JaCoCo dump must be persisted before merge");
    }

    private JsonNode json(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().getFirst();
        return new ObjectMapper().readTree(content.text());
    }

    private static List<String> uniqueProjectSelectors(int count) {
        List<String> selectors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            selectors.add(new UUID(1L, index + 1L).toString());
        }
        return List.copyOf(selectors);
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private record ChildCoverage(String agentArgLine, Path destination, int port) {
    }
}
