package com.nexus.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.integrations.AssistantIntegrationGenerator.CommandSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression tests for P3: per-format escaping. The command (shell) form, JSON and TOML have
 * distinct escaping rules; a path containing shell metacharacters, quotes or backslashes must stay
 * syntactically valid in every generated form.
 */
class AssistantIntegrationEscapingTest {

    private final AssistantIntegrationGenerator generator = new AssistantIntegrationGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static CommandSpec javaSpec(String runnerPath) {
        return new CommandSpec("java", List.of("-jar", runnerPath));
    }

    @Test
    void commandFormQuotesArgumentWithAmpersandEvenWithoutSpace() {
        // C:\a&b\... n'a pas d'espace mais contient & : sans quoting, cmd casserait la commande.
        String path = "C:\\a&b\\nexus-mcp-runner.jar";
        String command = generator.copilotCliCommand(javaSpec(path));
        assertTrue(command.contains('"' + path + '"'),
                "argument avec & entouré de guillemets : " + command);
    }

    @Test
    void commandFormQuotesProgramFilesX86Path() {
        String path = "C:\\Program Files (x86)\\nexus\\nexus-mcp-runner.jar";
        String command = generator.claudeUserCommand(javaSpec(path));
        assertTrue(command.contains('"' + path + '"'), command);
    }

    @Test
    void commandFormQuotesPathWithPercentAndBang() {
        String path = "C:\\weird%dir!\\runner.jar";
        String command = generator.codexCommand(javaSpec(path));
        assertTrue(command.contains('"' + path + '"'), command);
    }

    @Test
    void commandFormLeavesPlainTokenUnquoted() {
        String command = generator.copilotCliCommand(new CommandSpec("java", List.of("-jar", "runner.jar")));
        assertTrue(command.contains("-- java -jar runner.jar"),
                "token simple non quoté inutilement : " + command);
    }

    @Test
    void commandFormEscapesEmbeddedQuote() {
        String path = "C:\\has\"quote\\runner.jar";
        String command = generator.copilotCliCommand(javaSpec(path));
        assertTrue(command.contains("\\\""), "guillemet interne échappé : " + command);
    }

    @Test
    void jsonFormRoundTripsSpecialCharactersExactly() throws Exception {
        String path = "C:\\a&b (x86)\\has\"quote\\runner.jar";
        JsonNode root = objectMapper.readTree(generator.copilotCliJson(javaSpec(path)));
        // Jackson garantit une valeur JSON valide et un round-trip exact.
        assertEquals(path, root.path("mcpServers").path("nexus").path("args").get(1).asText());
    }

    @Test
    void tomlFormEscapesBackslashAndQuote() {
        String path = "C:\\a&b\\has\"quote\\runner.jar";
        String toml = generator.codexDesktopToml(javaSpec(path));
        // Les backslashes Windows et le guillemet doivent être échappés en TOML basic string.
        assertTrue(toml.contains("\\\\a&b\\\\"), "backslashes échappés : " + toml);
        assertTrue(toml.contains("has\\\"quote"), "guillemet échappé : " + toml);
    }
}
