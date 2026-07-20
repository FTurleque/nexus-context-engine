package com.nexus.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantIntegrationGeneratorTest {

    @TempDir
    Path temporaryDirectory;

    private final AssistantIntegrationGenerator generator = new AssistantIntegrationGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generatesCopilotCliCommandForTheNexusStdioRunner() {
        Path runner = temporaryDirectory.resolve("nexus mcp").resolve("nexus-mcp-runner.jar");

        String command = generator.copilotCliCommand(runner);

        assertTrue(command.startsWith("copilot mcp add nexus"));
        assertTrue(command.contains("--tools \"*\""));
        assertTrue(command.contains("-- java -jar \""));
        assertTrue(command.contains(runner.toAbsolutePath().normalize().toString()));
    }

    @Test
    void generatesCopilotCliJsonWithoutSecrets() throws Exception {
        Path runner = temporaryDirectory.resolve("nexus-mcp-runner.jar");

        JsonNode root = objectMapper.readTree(generator.copilotCliJson(runner));
        JsonNode server = root.path("mcpServers").path("nexus");

        assertEquals("stdio", server.path("type").asText());
        assertEquals("java", server.path("command").asText());
        assertEquals("-jar", server.path("args").get(0).asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(1).asText());
        assertEquals("*", server.path("tools").get(0).asText());
        assertTrue(server.path("env").isObject());
        assertEquals(0, server.path("env").size());
        assertFalse(root.toString().toLowerCase().contains("token"));
        assertFalse(root.toString().toLowerCase().contains("secret"));
    }

    @Test
    void generatesJetBrainsCopilotMcpJsonUsingServersSchema() throws Exception {
        Path runner = temporaryDirectory.resolve("nexus-mcp-runner.jar");

        JsonNode root = objectMapper.readTree(generator.copilotJetBrainsJson(runner));
        JsonNode server = root.path("servers").path("nexus");

        assertEquals("stdio", server.path("type").asText());
        assertEquals("java", server.path("command").asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(1).asText());
    }

    @Test
    void generatesClaudeProjectConfigurationAndScopedCommands() throws Exception {
        Path runner = temporaryDirectory.resolve("nexus mcp").resolve("nexus-mcp-runner.jar");

        JsonNode root = objectMapper.readTree(generator.claudeProjectJson(runner));
        JsonNode server = root.path("mcpServers").path("nexus");

        assertEquals("stdio", server.path("type").asText());
        assertEquals("java", server.path("command").asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(1).asText());
        assertTrue(generator.claudeProjectCommand(runner).contains("--scope project"));
        assertTrue(generator.claudeUserCommand(runner).contains("--scope user"));
        assertTrue(generator.claudeProjectCommand(runner).contains("-- java -jar \""));
    }
}
