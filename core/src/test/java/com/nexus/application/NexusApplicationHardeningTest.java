package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.search.QueryPolicy;
import com.nexus.search.ResultLimitPolicy;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertFalse(empty.allProjectsReady());
        assertFalse(empty.degraded());
        assertEquals(0, empty.registeredProjects());

        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        application.registerProject(projectRoot, "demo");

        NexusApplication.ReadinessSnapshot registered = application.readiness();
        assertTrue(registered.operational());
        assertFalse(registered.allProjectsReady());
        assertFalse(registered.degraded());
    }

    @Test
    void publicSearchSurfacesRejectExcessiveResultLimits() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("limit-home")),
                SemanticSearchConfiguration.disabled());
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("limit-project"));
        Files.writeString(projectRoot.resolve("App.java"), "class App {}\n");
        var project = application.registerProject(projectRoot, "limit-demo");
        application.index(project.id(), false, false);

        assertThrows(IllegalArgumentException.class, () ->
                application.search(project.id(), "App", ResultLimitPolicy.MAX_RESULT_LIMIT + 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                application.findSymbols(project.id(), "App", ResultLimitPolicy.MAX_RESULT_LIMIT + 1));
        assertThrows(IllegalArgumentException.class, () ->
                application.findUsages(project.id(), "App", ResultLimitPolicy.MAX_RESULT_LIMIT + 1));
    }

    @Test
    void everyPublicQuerySurfaceRejectsOversizedUtf8BeforeProjectOrEngineResolution() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("query-home")),
                SemanticSearchConfiguration.disabled());
        UUID unknownProject = UUID.randomUUID();
        String oversized = "é".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES / 2 + 1);

        assertOversized(() -> application.search(unknownProject, oversized, 10, false));
        assertOversized(() -> application.searchAcrossProjects(List.of(unknownProject), oversized, 10, false));
        assertOversized(() -> application.context(unknownProject, oversized, 100, Set.of(), Map.of(), false));
        assertOversized(() -> application.contextAcrossProjects(
                List.of(unknownProject), oversized, 100, Set.of(), Map.of(), false));
        assertOversized(() -> application.findSymbols(unknownProject, oversized, 10));
        assertOversized(() -> application.findUsages(unknownProject, oversized, 10));
    }

    @Test
    void doesNotReinterpretAValidUnknownUuidAsAProjectName() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("uuid-home")),
                SemanticSearchConfiguration.disabled());
        UUID unknown = UUID.randomUUID();

        String unknownSelector = unknown.toString();
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> application.resolveProject(unknownSelector));

        assertTrue(failure.getMessage().contains(unknownSelector));
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
        UUID projectId = project.id();
        try (ProjectIndexLockManager.LockHandle ignored = secondOwner.acquire(projectId)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> application.importMinos(projectId, "{}"));
            assertTrue(failure.getMessage().contains("mutation d'index"));
        }
    }

    private static void assertOversized(ThrowingCall call) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, call::run);
        assertTrue(failure.getMessage().contains("octets UTF-8"), failure.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
