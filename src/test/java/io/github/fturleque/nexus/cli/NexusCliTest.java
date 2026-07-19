package io.github.fturleque.nexus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fturleque.nexus.config.NexusPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static void configureNexusHome(Path nexusHome) {
        System.setProperty(NexusPaths.HOME_PROPERTY, nexusHome.toString());
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static CliExecution execute(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            int exitCode = NexusCli.execute(args, out, err);
            return new CliExecution(
                    exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private record CliExecution(int exitCode, String stdout, String stderr) {
    }
}
