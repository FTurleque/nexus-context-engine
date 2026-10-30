package com.nexus.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.QueryPolicy;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrat MCP de bout en bout via un vrai client STDIO : un test par outil exposé.
 *
 * <p>Le projet indexé, la home NEXUS et le processus serveur MCP sont préparés une fois par
 * classe. Aucun test ne suppose l'ordre d'exécution : le seul test qui mute l'état (suppression
 * de fichiers indexés) construit sa propre home, ses propres projets et son propre serveur.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NexusMcpServerIntegrationTest {

    private static final String CHILD_JACOCO_PROPERTY = "nexus.mcp.child.jacoco.argLine";
    private static final String CHILD_JACOCO_DEST_FILE_PROPERTY = "nexus.mcp.child.jacoco.destFile";
    private static final String NATIVE_ACCESS_ARGUMENT = "--enable-native-access=ALL-UNNAMED";
    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final String SOURCE_PATH = "src/main/java/demo/OrderService.java";
    private static final String QUERY = "OrderService process order";
    private static final String FIXTURE_SECRET = "synthetic-unquoted-secret-123456";
    private static final List<String> INTERNAL_MODEL_MARKERS = List.of(
            "com.nexus.",
            "ProjectDescriptor",
            "RankedCandidate",
            "ContextBundle",
            "FederatedSearchHit");

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
    static Path classDirectory;

    private final ObjectMapper mapper = new ObjectMapper();

    private Path nexusHome;
    private Path projectRoot;
    private NexusApplication application;
    private ProjectDescriptor project;
    private NexusApplication.SearchOperation directSearch;
    private NexusApplication.ContextOperation directContext;
    private McpServerProcess server;
    private boolean childCoverageDumped;

    @BeforeAll
    void startServerOnAnIndexedProject() throws Exception {
        nexusHome = classDirectory.resolve("Build Secret");
        projectRoot = Files.createDirectories(classDirectory.resolve("Alice Smith"));
        writeOrderService(projectRoot);

        application = NexusApplication.create(new NexusPaths(nexusHome));
        project = application.registerProject(projectRoot, "mcp-validation");
        application.index(project.id(), true, false);

        directSearch = application.search(project.id(), QUERY, 5, true);
        directContext = application.context(project.id(), QUERY, 200, Set.of(), Map.of(), false);

        server = startServer(nexusHome);
    }

    @AfterAll
    void stopServer() throws Exception {
        try {
            if (server != null) {
                server.stop();
            }
        } finally {
            if (application != null) {
                application.close();
            }
        }
    }

    @Test
    void listsExactlyTheExpectedTools() {
        var tools = server.client().listTools().tools().stream().map(McpSchema.Tool::name).toList();
        assertEquals(EXPECTED_TOOLS.size(), tools.size());
        assertEquals(EXPECTED_TOOLS, Set.copyOf(tools));
    }

    @Test
    void listProjectsReturnsTheRegisteredProjectWithoutRootPath() throws Exception {
        McpSchema.CallToolResult projectList = call("list_projects", Map.of());
        assertFalse(Boolean.TRUE.equals(projectList.isError()));
        JsonNode projectListJson = json(projectList);
        assertEquals(1, projectListJson.size());
        assertFalse(projectListJson.get(0).has("rootPath"));
        assertEquals(project.id().toString(), projectListJson.get(0).path("id").asText());
        assertNoLeak(projectList);
    }

    @Test
    void searchCodeReturnsTheDirectSearchResultWithAPublicPath() throws Exception {
        McpSchema.CallToolResult searchResult = call("search_code", Map.of(
                "project", project.id().toString(),
                "query", QUERY,
                "limit", 5,
                "explain", true));
        assertFalse(Boolean.TRUE.equals(searchResult.isError()));
        JsonNode searchJson = json(searchResult);
        assertEquals(SOURCE_PATH, searchJson.path("results").get(0).path("path").asText());
        assertFalse(searchJson.path("project").has("rootPath"));
        assertEquals(
                directSearch.results().getFirst().score(),
                searchJson.path("results").get(0).path("score").asDouble(),
                0.0000001d);
        assertNoLeak(searchResult);
    }

    @Test
    void searchCodeRejectsAnOversizedUtf8Query() throws Exception {
        String oversizedUtf8 = "é".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES / 2 + 1);
        McpSchema.CallToolResult oversizedResult = call("search_code", Map.of(
                "project", project.id().toString(),
                "query", oversizedUtf8,
                "limit", 1));
        assertTrue(Boolean.TRUE.equals(oversizedResult.isError()));
        JsonNode oversizedJson = json(oversizedResult);
        assertEquals("nexus_tool_error", oversizedJson.path("error").asText());
        assertTrue(oversizedJson.path("message").asText().contains("octets UTF-8"));
        assertNoLeak(oversizedResult);
    }

    @Test
    void searchCodeRejectsSensitiveSelectorsAndUnexpectedArgumentsWithoutEchoingThem() throws Exception {
        for (String sensitive : List.of("/tmp/Build Secret/cache/file.txt", "C:\\Users\\Alice Smith\\Nexus Project\\src\\App.java",
                "\\\\server\\Private Share\\Nexus Project\\file.java", "file:///home/alice/My%20Project/src/App.java")) {
            var rejected = call("search_code", Map.of("project", sensitive, "query", "x"));
            assertEquals(Boolean.TRUE, rejected.isError());
            String payload = mapper.writeValueAsString(rejected);
            for (String secret : List.of("Build Secret", "Alice Smith", "Private Share", "My%20Project", "cache", "file.java")) {
                assertFalse(payload.contains(secret), payload);
            }
            assertNoLeak(rejected);
        }
        var unexpected = call("search_code", Map.of(
                "project", project.id().toString(), "query", "x", "unexpected", true));
        assertEquals(Boolean.TRUE, unexpected.isError());
        assertNoLeak(unexpected);
    }

    @Test
    void buildContextRedactsSecretsAndMatchesTheDirectContextBudget() throws Exception {
        McpSchema.CallToolResult contextResult = call("build_context", Map.of(
                "project", "mcp-validation",
                "query", QUERY,
                "tokenBudget", 200));
        assertFalse(Boolean.TRUE.equals(contextResult.isError()));
        JsonNode contextJson = json(contextResult);
        assertFalse(contextJson.toString().contains("123456789"));
        assertFalse(contextJson.toString().contains(FIXTURE_SECRET));
        assertTrue(contextJson.toString().contains("[REDACTED]"));
        assertEquals(directContext.bundle().tokenBudget(), contextJson.path("tokenBudget").asInt());
        assertEquals(directContext.bundle().estimatedTokens(), contextJson.path("estimatedTokens").asInt());
        assertEquals(SOURCE_PATH, contextJson.path("items").get(0).path("path").asText());
        assertNoLeak(contextResult);
    }

    @Test
    void explainContextReturnsAnExplainedContext() throws Exception {
        McpSchema.CallToolResult explainContext = call("explain_context", Map.of(
                "project", project.id().toString(),
                "query", QUERY,
                "tokenBudget", 200));
        assertFalse(Boolean.TRUE.equals(explainContext.isError()));
        assertTrue(json(explainContext).path("explain").asBoolean());
        assertNoLeak(explainContext);
    }

    @Test
    void findSymbolReturnsTheIndexedSymbol() throws Exception {
        McpSchema.CallToolResult symbolResult = call("find_symbol", Map.of(
                "project", project.id().toString(),
                "query", "OrderService"));
        assertFalse(Boolean.TRUE.equals(symbolResult.isError()));
        assertTrue(json(symbolResult).path("symbols").size() > 0);
        assertNoLeak(symbolResult);
    }

    @Test
    void findUsagesReturnsARelationArray() throws Exception {
        McpSchema.CallToolResult usagesResult = call("find_usages", Map.of(
                "project", project.id().toString(),
                "symbol", "OrderService",
                "limit", 20));
        assertFalse(Boolean.TRUE.equals(usagesResult.isError()));
        assertTrue(json(usagesResult).path("relations").isArray());
        assertNoLeak(usagesResult);
    }

    @Test
    void searchAcrossProjectsDeduplicatesTheScope() throws Exception {
        List<String> duplicateHeavyScope = new ArrayList<>();
        for (int repetition = 0; repetition < FederatedScopePolicy.MAX_PROJECTS + 50; repetition++) {
            duplicateHeavyScope.add(project.id().toString());
        }
        McpSchema.CallToolResult duplicateScope = call("search_across_projects", Map.of(
                "projects", duplicateHeavyScope,
                "query", "OrderService",
                "limit", 1));
        assertFalse(Boolean.TRUE.equals(duplicateScope.isError()));
        assertEquals(1, json(duplicateScope).path("projects").size());
        assertNoLeak(duplicateScope);
    }

    @Test
    void searchAcrossProjectsRejectsTooManyDistinctProjects() throws Exception {
        McpSchema.CallToolResult oversizedScope = call("search_across_projects", Map.of(
                "projects", uniqueProjectSelectors(FederatedScopePolicy.MAX_PROJECTS + 1),
                "query", "OrderService",
                "limit", 1));
        assertTrue(Boolean.TRUE.equals(oversizedScope.isError()));
        JsonNode oversizedScopeJson = json(oversizedScope);
        assertEquals("nexus_tool_error", oversizedScopeJson.path("error").asText());
        assertEquals(
                FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE,
                oversizedScopeJson.path("message").asText());
        assertNoLeak(oversizedScope);
    }

    @Test
    void buildContextAcrossProjectsCoversTheRequestedProject() throws Exception {
        McpSchema.CallToolResult federatedContext = call("build_context_across_projects", Map.of(
                "projects", List.of(project.id().toString()),
                "query", QUERY,
                "tokenBudget", 200));
        assertFalse(Boolean.TRUE.equals(federatedContext.isError()));
        JsonNode federatedJson = json(federatedContext);
        assertEquals(1, federatedJson.path("projects").size());
        assertFalse(federatedJson.toString().contains(FIXTURE_SECRET));
        assertNoLeak(federatedContext);
    }

    @Test
    void explainContextAcrossProjectsReturnsAnExplainedContext() throws Exception {
        McpSchema.CallToolResult explainFederatedContext = call("explain_context_across_projects", Map.of(
                "projects", List.of("mcp-validation"),
                "query", QUERY,
                "tokenBudget", 200));
        assertFalse(Boolean.TRUE.equals(explainFederatedContext.isError()));
        assertTrue(json(explainFederatedContext).path("explain").asBoolean());
        assertNoLeak(explainFederatedContext);
    }

    /**
     * Mute l'état (fichiers indexés supprimés) : home, projets et serveur propres au test, pour
     * que les autres tests restent indépendants de l'ordre d'exécution.
     */
    @Test
    void explainToolsDoNotLeakPathsWhenIndexedFilesHaveDisappeared(@TempDir Path ownDirectory) throws Exception {
        Path ownHome = ownDirectory.resolve("Build Secret");
        Path firstRoot = Files.createDirectories(ownDirectory.resolve("Alice Smith"));
        Path firstSource = writeOrderService(firstRoot);
        Path secondRoot = Files.createDirectory(ownDirectory.resolve("second-project"));
        Path secondSource = Files.writeString(secondRoot.resolve("OrderService.java"), "class OrderService {}");

        ProjectDescriptor first;
        ProjectDescriptor second;
        try (NexusApplication ownApplication = NexusApplication.create(new NexusPaths(ownHome))) {
            // Le nom ne doit pas être « first-project » : sous Windows, la séquence échappée
            // first-project\" du JSON ferait échouer l'assertion sur first-project + séparateur.
            first = ownApplication.registerProject(firstRoot, "mcp-validation");
            ownApplication.index(first.id(), true, false);
            second = ownApplication.registerProject(secondRoot, "second-project");
            ownApplication.index(second.id(), true, false);
        }
        Files.delete(firstSource);
        Files.delete(secondSource);

        McpServerProcess ownServer = startServer(ownHome);
        try {
            for (String tool : List.of("explain_context", "explain_context_across_projects")) {
                Map<String, Object> arguments = new LinkedHashMap<>();
                arguments.put("query", "OrderService");
                arguments.put("tokenBudget", 500);
                if (tool.endsWith("across_projects")) {
                    arguments.put("projects", List.of(first.id().toString(), second.id().toString()));
                } else {
                    arguments.put("project", first.id().toString());
                }
                var missing = ownServer.client().callTool(
                        McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
                assertFalse(Boolean.TRUE.equals(missing.isError()));
                String completePayload = mapper.writeValueAsString(missing);
                assertFalse(completePayload.contains("first-project" + File.separator));
                // Le résultat MCP contient lui-même du JSON dans TextContent : inspecter
                // aussi l'arbre décodé évite de dépendre du nombre d'échappements JSON.
                String decoded = json(missing).toString();
                for (Path hidden : List.of(firstRoot, secondRoot, ownHome)) {
                    assertFalse(decoded.contains("Alice Smith"), decoded);
                    assertFalse(decoded.contains("Build Secret"), decoded);
                    assertFalse(decoded.contains(hidden.toString().replace("\\", "\\\\")), decoded);
                    assertFalse(decoded.contains(hidden.toString().replace('\\', '/')), decoded);
                }
                assertFalse(decoded.contains(FIXTURE_SECRET), decoded);
                for (String marker : INTERNAL_MODEL_MARKERS) {
                    assertFalse(decoded.contains(marker), decoded);
                }
            }
        } finally {
            ownServer.stop();
        }
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        return server.client().callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    /**
     * Non-fuite commune : ni chemin absolu de la fixture (home ou racine projet, quelle que soit
     * la forme des séparateurs), ni secret de la fixture, ni type du modèle interne du cœur.
     */
    private void assertNoLeak(McpSchema.CallToolResult result) throws Exception {
        String payload = mapper.writeValueAsString(result);
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        for (String inspected : List.of(payload, text)) {
            assertFalse(inspected.contains("Alice Smith"), inspected);
            assertFalse(inspected.contains("Build Secret"), inspected);
            for (Path hidden : List.of(projectRoot, nexusHome, classDirectory)) {
                assertFalse(inspected.contains(hidden.toString()), inspected);
                assertFalse(inspected.contains(hidden.toString().replace("\\", "\\\\")), inspected);
                assertFalse(inspected.contains(hidden.toString().replace('\\', '/')), inspected);
            }
            assertFalse(inspected.contains(FIXTURE_SECRET), inspected);
            for (String marker : INTERNAL_MODEL_MARKERS) {
                assertFalse(inspected.contains(marker), inspected);
            }
        }
    }

    private static Path writeOrderService(Path root) throws Exception {
        Path source = root.resolve(SOURCE_PATH);
        Files.createDirectories(source.getParent());
        return Files.writeString(source, """
                package demo;
                public class OrderService {
                    // {"password": 123456789, "api_key": synthetic-unquoted-secret-123456}
                    public String processOrder(String id) {
                        return "processed-" + id;
                    }
                }
                """);
    }

    /**
     * Processus serveur MCP STDIO réel, avec agent JaCoCo optionnel. Le premier dump de la
     * classe remplace le fichier de couverture du processus enfant, les suivants s'y ajoutent.
     */
    private final class McpServerProcess {

        private final McpSyncClient client;
        private final ChildCoverage coverage;

        private McpServerProcess(McpSyncClient client, ChildCoverage coverage) {
            this.client = client;
            this.coverage = coverage;
        }

        McpSyncClient client() {
            return client;
        }

        void stop() throws Exception {
            try {
                if (coverage != null) {
                    dumpChildCoverage(coverage);
                }
            } finally {
                assertTrue(client.closeGracefully(), "The MCP server process must stop before the test completes");
            }
        }
    }

    private McpServerProcess startServer(Path home) throws Exception {
        ChildCoverage childCoverage = childCoverage();
        List<String> serverArguments = new ArrayList<>();
        serverArguments.add(NATIVE_ACCESS_ARGUMENT);
        if (childCoverage != null) {
            serverArguments.add(childCoverage.agentArgLine());
        }
        serverArguments.add("-Dnexus.home=" + home.toAbsolutePath());
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
        client.initialize();
        return new McpServerProcess(client, childCoverage);
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

    private void dumpChildCoverage(ChildCoverage coverage) throws Exception {
        ExecDumpClient dumpClient = new ExecDumpClient();
        dumpClient.setRetryCount(20);
        dumpClient.setRetryDelay(100);
        ExecFileLoader executionData = dumpClient.dump(LOOPBACK_ADDRESS, coverage.port());
        assertFalse(
                executionData.getExecutionDataStore().getContents().isEmpty(),
                "The MCP child JVM must expose JaCoCo execution data before it is stopped");
        boolean append = childCoverageDumped;
        if (!append) {
            Files.deleteIfExists(coverage.destination());
        }
        executionData.save(coverage.destination().toFile(), append);
        childCoverageDumped = true;
        assertTrue(Files.size(coverage.destination()) > 0, "The MCP child JaCoCo dump must be persisted before merge");
    }

    private JsonNode json(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().getFirst();
        return mapper.readTree(content.text());
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
