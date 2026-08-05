package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.index.ProjectIndexLockManager;
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

    @Test
    void minosImportCannotRaceAnotherProjectIndexMutation() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("minos-home"));
        NexusApplication application = NexusApplication.create(paths, SemanticSearchConfiguration.disabled());
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("minos-project"));
        Files.writeString(projectRoot.resolve("App.java"), "class App {}\n");
        var project = application.registerProject(projectRoot, "minos-demo");
        application.index(project.id(), false, false);

        ProjectIndexLockManager secondOwner = ProjectIndexLockManager.fileBacked(paths);
        try (ProjectIndexLockManager.LockHandle ignored = secondOwner.acquire(project.id())) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> application.importMinos(project.id(), "{}"));
            assertTrue(failure.getMessage().contains("mutation d'index"));
        }
    }
}
