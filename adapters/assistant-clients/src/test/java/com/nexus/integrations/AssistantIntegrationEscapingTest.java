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
                "C:\\weird%VAR%\\runner.jar",
                "C:\\weird!var!\\runner.jar",
                "C:\\weird$var\\runner.jar",
                "C:\\weird`var\\runner.jar",
                "C:\\has\"quote\\runner.jar",
                "C:\\has'quote\\runner.jar")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> generator.codexCommand(javaSpec(path)),
                    path);
            assertTrue(failure.getMessage().contains("JSON/TOML"), failure.getMessage());
        }
    }

    @Test
    void commandFormRejectsEmptyArgumentsInsteadOfSilentlyDroppingThem() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AssistantIntegrationGenerator.renderCommand(new CommandSpec("java", List.of(""))));
        assertTrue(failure.getMessage().contains("argument vide"), failure.getMessage());
        assertTrue(failure.getMessage().contains("JSON/TOML"), failure.getMessage());
    }

    @Test
    void jsonFormRoundTripsStructuredArgvIncludingShellAmbiguities() throws Exception {
        List<String> expected = List.of(
                "",
                "C:\\a&b (x86)\\backslash\\path",
                "has\"quote",
                "%VAR%",
                "$var",
                "!var!",
                "`literal`",
                "\u00e9t\u00e9\u6f22\u5b57");
        CommandSpec spec = new CommandSpec("C:\\Program Files\\java.exe", expected);
        JsonNode server = objectMapper.readTree(generator.copilotCliJson(spec))
                .path("mcpServers").path("nexus");
        assertEquals(spec.command(), server.path("command").asText());
        assertEquals(expected.size(), server.path("args").size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index), server.path("args").get(index).asText());
        }
    }

    @Test
    void tomlFormEscapesStructuredBackslashesQuotesAndControls() {
        String path = "C:\\a&b\\has\"quote\\runner.jar";
        CommandSpec spec = new CommandSpec("java", List.of(path, "", "%VAR%", "$var", "!var!", "\u00e9t\u00e9\u6f22\u5b57"));
        String toml = generator.codexDesktopToml(spec);
        assertTrue(toml.contains("\\\\a&b\\\\"), "backslashes échappés : " + toml);
        assertTrue(toml.contains("has\\\"quote"), "guillemet échappé : " + toml);
        assertTrue(toml.contains("\"\""), "argument vide structuré : " + toml);
        assertTrue(toml.contains("\"%VAR%\""), "pourcentage littéral structuré : " + toml);
        assertTrue(toml.contains("\"$var\""), "dollar littéral structuré : " + toml);
        assertTrue(toml.contains("\"!var!\""), "exclamation littérale structurée : " + toml);
        assertTrue(toml.contains("\"\u00e9t\u00e9\u6f22\u5b57\""), "Unicode structuré : " + toml);
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
        List<String> expected = List.of(
                "space & value",
                "paren(value)",
                "semi;comma,value",
                "C:\\literal\\backslash\\path",
                "\u00e9t\u00e9\u6f22\u5b57");

        Path cmdOutput = work.resolve("cmd argv.txt");
        CommandSpec cmdSpec = captureSpec(powershell, capture, cmdOutput, expected);
        String portable = AssistantIntegrationGenerator.renderCommand(cmdSpec);
        Process cmd = new ProcessBuilder(cmdExe, "/D", "/S", "/C", portable).start();
        assertEquals(0, cmd.waitFor());
        assertEquals(expected, Files.readAllLines(cmdOutput, StandardCharsets.UTF_8));

        Path psOutput = work.resolve("powershell argv.txt");
        CommandSpec psSpec = captureSpec(powershell, capture, psOutput, expected);
        String psPortable = AssistantIntegrationGenerator.renderCommand(psSpec);
        Path invocation = work.resolve("invoke portable command.ps1");
        writeUtf8Bom(invocation, "& " + psPortable + "\r\n");
        Process ps = new ProcessBuilder(
                powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", invocation.toString()).start();
        assertEquals(0, ps.waitFor());
        assertEquals(expected, Files.readAllLines(psOutput, StandardCharsets.UTF_8));
    }

    private static void writeUtf8Bom(Path path, String content) throws Exception {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[payload.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(payload, 0, bytes, 3, payload.length);
        Files.write(path, bytes);
    }

    private static CommandSpec captureSpec(
            String powershell,
            Path capture,
            Path output,
            List<String> expected) {
        java.util.ArrayList<String> args = new java.util.ArrayList<>(List.of(
                "-NoLogo", "-NoProfile", "-NonInteractive", "-File", capture.toString(), output.toString()));
        args.addAll(expected);
        return new CommandSpec(powershell, args);
    }
}
