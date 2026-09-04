package com.nexus.index.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScannerFileSizeTest {

    @TempDir
    Path projectRoot;

    @Test
    void skipsOversizedSupportedFileBeforeIndexing() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/Large.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Large { String value = \"01234567890123456789\"; }");

        ProjectScanResult result = new ProjectScanner(16).scanWithDiagnostics(projectRoot);

        assertEquals(0, result.files().size());
        assertEquals(1, result.skippedFiles());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().getFirst().contains("src/main/java/demo/Large.java"));
        assertTrue(result.diagnostics().getFirst().contains("limite 16 octets"));
    }
}
