package com.nexus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private String previousNexusHome;

    @BeforeEach
    void rememberNexusHome() {
        previousNexusHome = System.getProperty(NexusPaths.HOME_PROPERTY);
    }

    @AfterEach
    void restoreNexusHome() {
        if (previousNexusHome == null) {
            System.clearProperty(NexusPaths.HOME_PROPERTY);
        } else {
            System.setProperty(NexusPaths.HOME_PROPERTY, previousNexusHome);
        }
    }

    @Test
    void rendersHelpAsJsonWithoutInitializingAProject() throws Exception {
        CliExecution execution = execute("--help", "--json");

        assertEquals(NexusCli.EXIT_SUCCESS, execution.exitCode());
        assertTrue(execution.stderr().isBlank());
        JsonNode payload = JSON.readTree(execution.stdout());
        assertEquals("help", payload.path("command").asText());
        assertTrue(payload.path("commands").isArray());
        assertTrue(payload.path("commands").toString().contains("context"));
        assertTrue(payload.path("commands").toString().contains("--deep-java"));
        assertTrue(payload.path("commands").toString().contains("minos-import"));
    }

    @Test
    void returnsStructuredUsageErrorForUnknownCommand() throws Exception {
        CliExecution execution = execute("unknown-command", "--json");

        assertEquals(NexusCli.EXIT_USAGE_ERROR, execution.exitCode());
        assertTrue(execution.stdout().isBlank());
        JsonNode error = JSON.readTree(execution.stderr());
        assertTrue(error.path("error").asBoolean());
        assertEquals(NexusCli.EXIT_USAGE_ERROR, error.path("exitCode").asInt());
        assertTrue(error.path("message").asText().contains("Commande inconnue"));
    }

    @Test
    void executesTheMvpFlowThroughStableJsonOutputs() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        write(projectRoot, "src/main/java/demo/OrderService.java", """
                package demo;
                public class OrderService {
                    public void processOrder() {}
                }
                """);
        write(projectRoot, "src/test/java/demo/OrderServiceTest.java", """
                package demo;
                public class OrderServiceTest {
                    void verifiesOrderService() { new OrderService().processOrder(); }
                }
                """);
        configureNexusHome(temporaryDirectory.resolve("nexus-home"));

        CliExecution add = execute("project", "add", projectRoot.toString(), "demo-cli", "--json");
        assertEquals(NexusCli.EXIT_SUCCESS, add.exitCode());
        assertEquals("demo-cli", JSON.readTree(add.stdout()).path("project").path("name").asText());

        CliExecution index = execute("index", "demo-cli", "--json");
        assertEquals(NexusCli.EXIT_SUCCESS, index.exitCode());
        JsonNode indexPayload = JSON.readTree(index.stdout());
        assertTrue(indexPayload.path("report").path("changedFiles").asInt() >= 2);
        assertEquals("READY", indexPayload.path("project").path("indexStatus").asText());

        CliExecution minosImport = executeWithInput(
                minosPayload(projectRoot),
                "minos-import", "demo-cli", "--json");
        assertEquals(NexusCli.EXIT_SUCCESS, minosImport.exitCode());
        JsonNode minosPayload = JSON.readTree(minosImport.stdout());
        assertNotNull(minosPayload);
        assertEquals("minos-import", minosPayload.path("command").asText());
        assertEquals("minos", minosPayload.path("sourceProvider").asText());
        assertEquals(1, minosPayload.path("symbols").asInt());
        assertEquals(0, minosPayload.path("relations").asInt());

        CliExecution search = execute(
                "search", "demo-cli", "OrderService", "--limit", "5", "--explain", "--json");
        assertEquals(NexusCli.EXIT_SUCCESS, search.exitCode());
        JsonNode searchPayload = JSON.readTree(search.stdout());
        assertTrue(searchPayload.path("results").size() > 0);
        assertTrue(searchPayload.path("results").get(0).path("path").asText().endsWith("OrderService.java"));
        assertTrue(searchPayload.path("results").get(0).path("reasons").isArray());
        assertTrue(searchPayload.path("durationMs").asLong() >= 0L);

        CliExecution context = execute(
                "context", "demo-cli", "OrderService", "--budget", "120", "--explain", "--json");
        assertEquals(NexusCli.EXIT_SUCCESS, context.exitCode());
        JsonNode contextPayload = JSON.readTree(context.stdout());
        assertTrue(contextPayload.path("estimatedTokens").asInt() <= 120);
        assertTrue(contextPayload.path("items").size() > 0);
        assertFalse(Path.of(contextPayload.path("items").get(0).path("path").asText()).isAbsolute());
        assertTrue(contextPayload.path("durationMs").asLong() >= 0L);

        CliExecution inspect = execute("inspect", "demo-cli", "--json");
        assertEquals(NexusCli.EXIT_SUCCESS, inspect.exitCode());
        JsonNode inspectPayload = JSON.readTree(inspect.stdout());
        assertEquals("READY", inspectPayload.path("project").path("indexStatus").asText());
        assertTrue(inspectPayload.path("index").path("files").asInt() >= 2);
    }

    private static String minosPayload(Path projectRoot) throws Exception {
        ObjectNode document = JSON.createObjectNode();
        document.put("contractVersion", "1");
        document.put("producer", "MINOS");
        ObjectNode project = document.putObject("project");
        project.put("id", "minos-project");
        project.put("name", "demo-cli");
        project.put("rootPath", projectRoot.toRealPath().toString());
        project.put("snapshotId", "m13-test");
        ObjectNode symbol = document.putArray("symbols").addObject();
        symbol.put("id", "order-service");
        symbol.put("symbolKey", "demo.OrderService");
        symbol.put("filePath", "src/main/java/demo/OrderService.java");
        symbol.put("kind", "CLASS");
        symbol.put("name", "OrderService");
        symbol.put("qualifiedName", "demo.OrderService");
        symbol.put("signature", "OrderService");
        symbol.put("language", "java");
        symbol.put("startLine", 2);
        symbol.put("endLine", 4);
        symbol.put("resolutionStatus", "RESOLVED");
        symbol.put("identityQuality", "CANONICAL");
        symbol.put("generated", false);
        document.putArray("relations");
        document.putArray("limitations");
        return JSON.writeValueAsString(document);
    }

    private static void configureNexusHome(Path nexusHome) {
        System.setProperty(NexusPaths.HOME_PROPERTY, nexusHome.toString());
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static CliExecution execute(String... args) {
        return executeWithStream(new ByteArrayInputStream(new byte[0]), args);
    }

    private static CliExecution executeWithInput(String input, String... args) {
        return executeWithStream(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), args);
    }

    private static CliExecution executeWithStream(InputStream input, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int exitCode = NexusCli.execute(args, input, out, err);
            return new CliExecution(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private record CliExecution(int exitCode, String stdout, String stderr) {
    }
}
