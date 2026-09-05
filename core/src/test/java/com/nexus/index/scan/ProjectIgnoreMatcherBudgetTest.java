package com.nexus.index.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIgnoreMatcherBudgetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void chargesExactIgnoreBytesOncePerDirectoryToExternalBudget() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("accounting"));
        byte[] gitignore = "build/\n".getBytes(StandardCharsets.UTF_8);
        byte[] nexusignore = "tmp/\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(".gitignore"), gitignore);
        Files.write(root.resolve(".nexusignore"), nexusignore);
        AtomicLong charged = new AtomicLong();

        ProjectIgnoreMatcher matcher = new ProjectIgnoreMatcher(
                root,
                (file, bytes) -> charged.addAndGet(bytes));
        matcher.registerDirectory(root);

        assertEquals(gitignore.length + nexusignore.length, charged.get());
    }

    @Test
    void rejectsSingleIgnoreFileBeyondPhysicalLimitBeforeParsing() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("single-limit"));
        Files.writeString(root.resolve(".gitignore"), "12345");

        IOException failure = assertThrows(
                IOException.class,
                () -> new ProjectIgnoreMatcher(root, 4L, 64L, (file, bytes) -> { }));

        assertTrue(failure.getMessage().contains("maximum 4 octets"), failure.getMessage());
    }

    @Test
    void rejectsCumulativeIgnoreBytesAcrossNestedDirectories() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("cumulative-limit"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve(".gitignore"), "#1234\n"); // 6 bytes
        Files.writeString(nested.resolve(".gitignore"), "#5678\n"); // 6 bytes
        AtomicLong charged = new AtomicLong();

        ProjectIgnoreMatcher matcher = new ProjectIgnoreMatcher(
                root,
                16L,
                10L,
                (file, bytes) -> charged.addAndGet(bytes));
        IOException failure = assertThrows(IOException.class, () -> matcher.registerDirectory(nested));

        assertEquals(6L, charged.get());
        assertTrue(failure.getMessage().contains("12 octets > limite 10 octets"), failure.getMessage());
    }
}
