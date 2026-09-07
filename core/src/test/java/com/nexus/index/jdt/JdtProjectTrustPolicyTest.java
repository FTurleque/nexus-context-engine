package com.nexus.index.jdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtProjectTrustPolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void failsClosedWithoutAnExplicitTrustedRoot() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));

        IOException missing = assertThrows(
                IOException.class,
                () -> ProcessBuilder.requireTrustedProjectDirectory(project.toFile(), null));
        assertTrue(missing.getMessage().contains(ProcessBuilder.TRUSTED_PROJECT_ROOTS_ENVIRONMENT_VARIABLE));

        IOException blank = assertThrows(
                IOException.class,
                () -> ProcessBuilder.requireTrustedProjectDirectory(project.toFile(), "   "));
        assertTrue(blank.getMessage().contains("non approuvé"));
    }

    @Test
    void trustsOnlyTheExactCanonicalProjectRoot() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path nested = Files.createDirectories(project.resolve("nested"));
        Path other = Files.createDirectories(temporaryDirectory.resolve("other"));
        String configured = other + File.pathSeparator + project;

        assertDoesNotThrow(() ->
                ProcessBuilder.requireTrustedProjectDirectory(project.toFile(), configured));

        assertThrows(
                IOException.class,
                () -> ProcessBuilder.requireTrustedProjectDirectory(nested.toFile(), configured),
                "faire confiance à une racine ne doit pas faire confiance à tous ses descendants");
    }

    @Test
    void ignoresInvalidOrMissingAllowlistEntriesInsteadOfBroadeningTrust() throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path missing = temporaryDirectory.resolve("missing");
        String configured = missing + File.pathSeparator + "\u0000invalid";

        assertThrows(
                IOException.class,
                () -> ProcessBuilder.requireTrustedProjectDirectory(project.toFile(), configured));
    }
}
