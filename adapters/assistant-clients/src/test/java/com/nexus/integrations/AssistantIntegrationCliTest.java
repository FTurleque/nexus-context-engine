package com.nexus.integrations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantIntegrationCliTest {

    @TempDir
    Path tempDir;

    @Test
    void mainPrintsUsageWhenRequiredArgumentsAreMissing() {
        String output = invokeMain("copilot-cli");
        assertTrue(output.contains("Usage:"), output);
        assertTrue(output.contains("copilot-jetbrains"), output);
    }

    @Test
    void mainCoversLegacyCommandAndJsonForms() {
        Path runner = tempDir.resolve("legacy runner.jar");

        String command = invokeMain("copilot-cli", runner.toString());
        assertTrue(command.contains("copilot mcp add nexus"), command);

        String json = invokeMain("copilot-cli", runner.toString(), "json");
        assertTrue(json.contains("\"mcpServers\""), json);
        assertTrue(json.contains("\"tools\""), json);
    }

    @Test
    void mainCoversNativeProfilesAndFormats() {
        Path java = tempDir.resolve("runtime").resolve("bin").resolve("java.exe");
        Path runner = tempDir.resolve("lib").resolve("nexus-mcp.jar");

        String jetbrains = invokeMain(
                "copilot-jetbrains", "native", java.toString(), runner.toString());
        assertTrue(jetbrains.contains("\"servers\""), jetbrains);

        String claudeCommand = invokeMain(
                "claude-project", "native", java.toString(), runner.toString(), "command");
        assertTrue(claudeCommand.contains("claude mcp add --scope project nexus"), claudeCommand);

        String claudeJson = invokeMain(
                "claude-project", "native", java.toString(), runner.toString(), "json");
        assertTrue(claudeJson.contains("\"mcpServers\""), claudeJson);
    }

    @Test
    void mainCoversDockerProfilesAndCodexFormats() {
        String codexCommand = invokeMain("codex-desktop", "docker", "nexus");
        assertTrue(codexCommand.contains("codex mcp add nexus -- docker exec -i nexus"), codexCommand);

        String codexToml = invokeMain("codex-desktop", "docker", "nexus", "toml");
        assertTrue(codexToml.contains("[mcp_servers.nexus]"), codexToml);

        String claudeCli = invokeMain("claude-cli", "docker", "nexus");
        assertTrue(claudeCli.contains("claude mcp add --scope user nexus"), claudeCli);

        String claudeAlias = invokeMain("claude-user", "docker", "nexus");
        assertTrue(claudeAlias.contains("claude mcp add --scope user nexus"), claudeAlias);

        String generic = invokeMain("generic", "docker", "nexus", "json");
        assertTrue(generic.contains("\"mcpServers\""), generic);
    }

    @Test
    void mainRejectsIncompleteExplicitModes() {
        IllegalArgumentException nativeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> invokeWithoutOutput("generic", "native", "java"));
        assertTrue(nativeFailure.getMessage().contains("mode native"), nativeFailure.getMessage());

        IllegalArgumentException dockerFailure = assertThrows(
                IllegalArgumentException.class,
                () -> invokeWithoutOutput("generic", "docker"));
        assertTrue(dockerFailure.getMessage().contains("mode docker"), dockerFailure.getMessage());
    }

    @Test
    void mainRejectsUnknownProfilesAndEmptyDockerContainerNames() {
        IllegalArgumentException profileFailure = assertThrows(
                IllegalArgumentException.class,
                () -> invokeWithoutOutput("unknown", "docker", "nexus"));
        assertTrue(profileFailure.getMessage().contains("Profil inconnu"), profileFailure.getMessage());

        IllegalArgumentException containerFailure = assertThrows(
                IllegalArgumentException.class,
                () -> invokeWithoutOutput("generic", "docker", "   "));
        assertTrue(containerFailure.getMessage().contains("containerName"), containerFailure.getMessage());
    }

    @Test
    void commandSpecRejectsBlankCommands() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new AssistantIntegrationGenerator.CommandSpec("   ", java.util.List.of()));
        assertTrue(failure.getMessage().contains("command ne peut pas être vide"), failure.getMessage());
    }

    private static void invokeWithoutOutput(String... args) {
        try (PrintWriter output = new PrintWriter(Writer.nullWriter())) {
            AssistantIntegrationGenerator.run(args, output);
        }
    }

    private static String invokeMain(String... args) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintWriter output = new PrintWriter(captured, true, StandardCharsets.UTF_8)) {
            AssistantIntegrationGenerator.run(args, output);
        }
        return captured.toString(StandardCharsets.UTF_8).trim();
    }
}
