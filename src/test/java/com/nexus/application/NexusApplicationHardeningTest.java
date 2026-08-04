package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusApplicationHardeningTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void distinguishesServiceReadinessFromProjectReadiness() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")),
                SemanticSearchConfiguration.disabled());

        NexusApplication.ReadinessSnapshot empty = application.readiness();
        assertTrue(empty.operational());
        assertTrue(empty.allProjectsReady());
        assertFalse(empty.degraded());

        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        application.registerProject(projectRoot, "demo");

        NexusApplication.ReadinessSnapshot registered = application.readiness();
        assertTrue(registered.operational());
        assertFalse(registered.allProjectsReady());
        assertFalse(registered.degraded());
    }

    @Test
    void doesNotReinterpretAValidUnknownUuidAsAProjectName() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("uuid-home")),
                SemanticSearchConfiguration.disabled());
        UUID unknown = UUID.randomUUID();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> application.resolveProject(unknown.toString()));

        assertTrue(failure.getMessage().contains(unknown.toString()));
        assertTrue(failure.getMessage().contains("Projet NEXUS introuvable"));
    }
}
