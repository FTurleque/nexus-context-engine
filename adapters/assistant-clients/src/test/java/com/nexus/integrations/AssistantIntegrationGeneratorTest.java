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
        assertTrue(command.contains("-- java --enable-native-access=ALL-UNNAMED -jar \""));
        assertTrue(command.contains(runner.toAbsolutePath().normalize().toString()));
    }

    @Test
    void generatesCopilotCliJsonWithoutSecrets() throws Exception {
        Path runner = temporaryDirectory.resolve("nexus-mcp-runner.jar");
        JsonNode root = objectMapper.readTree(generator.copilotCliJson(runner));
        JsonNode server = root.path("mcpServers").path("nexus");
        assertEquals("stdio", server.path("type").asText());
        assertEquals("java", server.path("command").asText());
        assertEquals(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT, server.path("args").get(0).asText());
        assertEquals("-jar", server.path("args").get(1).asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(2).asText());
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
        assertEquals(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT, server.path("args").get(0).asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(2).asText());
    }

    @Test
    void generatesClaudeProjectConfigurationAndScopedCommands() throws Exception {
        Path runner = temporaryDirectory.resolve("nexus mcp").resolve("nexus-mcp-runner.jar");
        JsonNode root = objectMapper.readTree(generator.claudeProjectJson(runner));
        JsonNode server = root.path("mcpServers").path("nexus");
        assertEquals("stdio", server.path("type").asText());
        assertEquals("java", server.path("command").asText());
        assertEquals(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT, server.path("args").get(0).asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(2).asText());
        assertTrue(generator.claudeProjectCommand(runner).startsWith("claude mcp add --scope project nexus --"));
        assertTrue(generator.claudeUserCommand(runner).startsWith("claude mcp add --scope user nexus --"));
        assertTrue(generator.claudeProjectCommand(runner)
                .contains("-- java --enable-native-access=ALL-UNNAMED -jar \""));
    }

    @Test
    void nativeModeUsesTheBundledJavaExecutable() throws Exception {
        Path java = temporaryDirectory.resolve("runtime").resolve("bin").resolve("java.exe");
        Path runner = temporaryDirectory.resolve("lib").resolve("nexus-mcp.jar");
        AssistantIntegrationGenerator.CommandSpec spec = generator.nativeMcp(java, runner);
        JsonNode server = objectMapper.readTree(generator.genericMcpJson(spec)).path("mcpServers").path("nexus");
        assertEquals(java.toAbsolutePath().normalize().toString(), server.path("command").asText());
        assertEquals(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT, server.path("args").get(0).asText());
        assertEquals("-jar", server.path("args").get(1).asText());
        assertEquals(runner.toAbsolutePath().normalize().toString(), server.path("args").get(2).asText());
    }

    @Test
    void dockerModeKeepsMcpOnStdioThroughDockerExec() throws Exception {
        AssistantIntegrationGenerator.CommandSpec spec = generator.dockerMcp("nexus-custom");
        JsonNode server = objectMapper.readTree(generator.copilotJetBrainsJson(spec)).path("servers").path("nexus");
        assertEquals("stdio", server.path("type").asText());
        assertEquals("docker", server.path("command").asText());
        assertEquals("exec", server.path("args").get(0).asText());
        assertEquals("-i", server.path("args").get(1).asText());
        assertEquals("nexus-custom", server.path("args").get(2).asText());
        assertEquals("java", server.path("args").get(3).asText());
        assertEquals(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT, server.path("args").get(4).asText());
        assertEquals("-jar", server.path("args").get(5).asText());
        assertEquals("/opt/nexus/lib/nexus-mcp.jar", server.path("args").get(6).asText());
        assertTrue(generator.copilotCliCommand(spec).contains(
                "-- docker exec -i nexus-custom java --enable-native-access=ALL-UNNAMED -jar"));
        assertTrue(generator.claudeUserCommand(spec).startsWith("claude mcp add --scope user nexus --"));
    }

    @Test
    void genericProfileUsesPortableMcpServersSchema() throws Exception {
        AssistantIntegrationGenerator.CommandSpec spec = generator.dockerMcp("nexus");
        JsonNode root = objectMapper.readTree(generator.genericMcpJson(spec));
        assertTrue(root.has("mcpServers"));
        assertEquals("docker", root.path("mcpServers").path("nexus").path("command").asText());
    }

    @Test
    void claudeCliUsesUserScopeWithBundledRuntime() {
        Path java = temporaryDirectory.resolve("runtime").resolve("bin").resolve("java.exe");
        Path runner = temporaryDirectory.resolve("lib").resolve("nexus-mcp.jar");
        AssistantIntegrationGenerator.CommandSpec spec = generator.nativeMcp(java, runner);
        String command = generator.claudeUserCommand(spec);
        assertTrue(command.startsWith("claude mcp add --scope user nexus --"));
        assertTrue(command.contains(java.toAbsolutePath().normalize().toString()));
        assertTrue(command.contains(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT));
        assertTrue(command.contains(runner.toAbsolutePath().normalize().toString()));
    }

    @Test
    void codexDesktopTomlUsesSharedCodexMcpConfiguration() {
        Path java = temporaryDirectory.resolve("runtime").resolve("bin").resolve("java.exe");
        Path runner = temporaryDirectory.resolve("lib").resolve("nexus-mcp.jar");
        AssistantIntegrationGenerator.CommandSpec spec = generator.nativeMcp(java, runner);
        String toml = generator.codexDesktopToml(spec);
        assertTrue(toml.contains("[mcp_servers.nexus]"));
        assertTrue(toml.contains("command = \""));
        assertTrue(toml.contains("args = [\"--enable-native-access=ALL-UNNAMED\", \"-jar\""));
        assertTrue(toml.contains("nexus-mcp.jar"));
    }

    @Test
    void codexCommandCanConfigureDesktopThroughTheSharedCodexConfig() {
        AssistantIntegrationGenerator.CommandSpec spec = generator.dockerMcp("nexus");
        String command = generator.codexCommand(spec);
        assertTrue(command.startsWith("codex mcp add nexus -- docker exec -i nexus"));
        assertTrue(command.contains(AssistantIntegrationGenerator.NATIVE_ACCESS_ARGUMENT));
        assertTrue(command.endsWith("/opt/nexus/lib/nexus-mcp.jar"));
    }
}
