package com.nexus.index.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScannerCorpusLimitTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsExactEntryBoundaryAndRejectsNextEntryEvenIfUnsupported() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("entry-budget"));
        Files.writeString(root.resolve("A.java"), "class A {}\n");
        Files.writeString(root.resolve("B.java"), "class B {}\n");

        ProjectScanner scanner = new ProjectScanner(1024L, 2, 1024L);
        assertEquals(2, scanner.scan(root).size());

        Files.write(root.resolve("payload.bin"), new byte[]{1});
        IOException failure = assertThrows(IOException.class, () -> scanner.scan(root));
        assertTrue(failure.getMessage().contains("3 entrées visitées > limite 2"), failure.getMessage());
    }

    @Test
    void acceptsExactByteBoundaryAndRejectsNextIndexedByte() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("byte-budget"));
        Files.writeString(root.resolve("A.java"), "12345");
        Files.writeString(root.resolve("B.java"), "67890");

        ProjectScanner scanner = new ProjectScanner(1024L, 10, 10L);
        assertEquals(2, scanner.scan(root).size());

        Files.writeString(root.resolve("C.java"), "x");
        IOException failure = assertThrows(IOException.class, () -> scanner.scan(root));
        assertTrue(failure.getMessage().contains("11 octets indexables > limite 10"), failure.getMessage());
    }

    @Test
    void perFileRejectionDoesNotConsumeIndexedByteBudget() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("per-file-budget"));
        Files.writeString(root.resolve("Large.java"), "01234567890");
        Files.writeString(root.resolve("Small.java"), "12345");

        ProjectScanResult result = new ProjectScanner(10L, 10, 5L).scanWithDiagnostics(root);

        assertEquals(1, result.files().size());
        assertEquals("Small.java", result.files().getFirst().relativePath());
        assertEquals(1, result.skippedFiles());
    }

    @Test
    void ignoreFilesConsumeTheSameGlobalByteBudgetAsIndexedSources() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("ignore-byte-budget"));
        Files.writeString(root.resolve(".gitignore"), "#1234\n"); // 6 UTF-8 bytes
        Files.writeString(root.resolve("A.java"), "12345"); // 5 UTF-8 bytes

        ProjectScanner scanner = new ProjectScanner(1024L, 10, 10L);
        IOException failure = assertThrows(IOException.class, () -> scanner.scan(root));

        assertTrue(failure.getMessage().contains("11 octets indexables > limite 10"), failure.getMessage());
    }
}
