package com.nexus.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.integrations.AssistantIntegrationGenerator.CommandSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-format escaping and real Windows argv non-regression tests. */
class AssistantIntegrationEscapingTest {

    private final AssistantIntegrationGenerator generator = new AssistantIntegrationGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static CommandSpec javaSpec(String runnerPath) {
        return new CommandSpec("java", List.of("-jar", runnerPath));
    }

    @Test
    void commandFormQuotesPortableMetacharacters() {
        String path = "C:\\a&b\\Program Files (x86)\\nexus-mcp-runner.jar";
        String command = generator.copilotCliCommand(javaSpec(path));
        assertTrue(command.contains('"' + path + '"'), command);
    }

    @Test
    void commandFormLeavesPlainTokenUnquoted() {
        String command = generator.copilotCliCommand(new CommandSpec("java", List.of("-jar", "runner.jar")));
        assertTrue(command.contains("-- java -jar runner.jar"), command);
    }

    @Test
    void commandFormRejectsShellAmbiguousCharactersInsteadOfPretendingUniversalQuoting() {
        for (String path : List.of(
                "C:\\weird%dir\\runner.jar",
                "C:\\weird!dir\\runner.jar",
                "C:\\weird$dir\\runner.jar",
                "C:\\weird`dir\\runner.jar",
                "C:\\has\"quote\\runner.jar",
                "C:\\has'quote\\runner.jar")) {
            assertThrows(IllegalArgumentException.class, () -> generator.codexCommand(javaSpec(path)), path);
        }
    }

    @Test
    void jsonFormRoundTripsSpecialCharactersExactly() throws Exception {
        String path = "C:\\a&b (x86)\\has\"quote\\runner.jar";
        JsonNode root = objectMapper.readTree(generator.copilotCliJson(javaSpec(path)));
        assertEquals(path, root.path("mcpServers").path("nexus").path("args").get(1).asText());
    }

    @Test
    void tomlFormEscapesBackslashAndQuote() {
        String path = "C:\\a&b\\has\"quote\\runner.jar";
        String toml = generator.codexDesktopToml(javaSpec(path));
        assertTrue(toml.contains("\\\\a&b\\\\"), "backslashes échappés : " + toml);
        assertTrue(toml.contains("has\\\"quote"), "guillemet échappé : " + toml);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void portableCommandPreservesActualArgvInCmdAndPowerShell() throws Exception {
        Path work = Files.createDirectories(tempDir.resolve("path with spaces & parens (x86)"));
        Path capture = work.resolve("capture.ps1");
        Files.writeString(capture, "[IO.File]::WriteAllLines($args[0], $args[1..($args.Length-1)])\r\n", StandardCharsets.UTF_8);

        String powershell = Path.of(System.getenv("SystemRoot"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        String cmdExe = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString();
        List<String> expected = List.of("space & value", "paren(value)", "semi;comma,value");

        Path cmdOutput = work.resolve("cmd argv.txt");
        CommandSpec cmdSpec = new CommandSpec(powershell, List.of(
                "-NoLogo", "-NoProfile", "-NonInteractive", "-File", capture.toString(),
                cmdOutput.toString(), expected.get(0), expected.get(1), expected.get(2)));
        String portable = AssistantIntegrationGenerator.renderCommand(cmdSpec);
        Process cmd = new ProcessBuilder(cmdExe, "/D", "/S", "/C", portable).start();
        assertEquals(0, cmd.waitFor());
        assertEquals(expected, Files.readAllLines(cmdOutput));

        Path psOutput = work.resolve("powershell argv.txt");
        CommandSpec psSpec = new CommandSpec(powershell, List.of(
                "-NoLogo", "-NoProfile", "-NonInteractive", "-File", capture.toString(),
                psOutput.toString(), expected.get(0), expected.get(1), expected.get(2)));
        String psPortable = AssistantIntegrationGenerator.renderCommand(psSpec);
        Path invocation = work.resolve("invoke portable command.ps1");
        Files.writeString(invocation, "& " + psPortable + "\r\n", StandardCharsets.UTF_8);
        Process ps = new ProcessBuilder(
                powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", invocation.toString()).start();
        assertEquals(0, ps.waitFor());
        assertEquals(expected, Files.readAllLines(psOutput));
    }
}
