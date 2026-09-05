package com.nexus.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusMcpServerLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exitsCleanlyWhenStdinReachesEof() throws Exception {
        Path nexusHome = temporaryDirectory.resolve("nexus-home");
        Process process = new ProcessBuilder(
                javaExecutable(),
                "--enable-native-access=ALL-UNNAMED",
                "-Dnexus.home=" + nexusHome.toAbsolutePath(),
                "-cp",
                System.getProperty("java.class.path"),
                NexusMcpServer.class.getName())
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
        try {
            process.getOutputStream().close();
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(exited, () -> "MCP server did not stop after stdin EOF. stderr=" + stderr);
            assertEquals(0, process.exitValue(), () -> "MCP server EOF shutdown failed. stderr=" + stderr);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
