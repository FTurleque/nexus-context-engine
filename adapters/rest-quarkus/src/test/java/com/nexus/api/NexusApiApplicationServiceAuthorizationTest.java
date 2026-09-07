package com.nexus.api;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusApiApplicationServiceAuthorizationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reappliesCurrentAllowlistToEveryPersistedProjectOperation() throws Exception {
        Path allowedParent = Files.createDirectories(temporaryDirectory.resolve("allowed"));
        Path allowedRoot = Files.createDirectories(allowedParent.resolve("project"));
        Path deniedRoot = Files.createDirectories(temporaryDirectory.resolve("previously-allowed"));
        Path nexusHome = temporaryDirectory.resolve("nexus-home");

        // The REST adapter's Quarkus test class loader does not service-load the
        // transitive SQLite JDBC driver for this direct core-fixture construction.
        // Load it explicitly without changing the adapter's production dependency graph.
        Class.forName("org.sqlite.JDBC");

        String previousRoots = System.getProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY);
        NexusApplication application = NexusApplication.createLongLived(
                new NexusPaths(nexusHome),
                SemanticSearchConfiguration.disabled());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        try {
            ProjectDescriptor allowed = application.registerProject(allowedRoot, "allowed");
            ProjectDescriptor denied = application.registerProject(deniedRoot, "denied");

            // Simule un redémarrage/configuration plus restrictive après persistence.
            System.setProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, allowedParent.toString());
            NexusApiApplicationService service = new NexusApiApplicationService(meterRegistry, application);

            assertEquals(
                    List.of(allowed.id()),
                    service.listProjects().stream().map(ProjectDescriptor::id).toList());
            assertEquals(allowed.id(), service.getProject(allowed.id()).id());

            assertDenied(() -> service.getProject(denied.id()));
            assertDenied(() -> service.index(denied.id(), false, false));
            assertDenied(() -> service.inspect(denied.id()));
            assertDenied(() -> service.search(denied.id(), "query", 1, false));
            assertDenied(() -> service.searchAcrossProjects(List.of(denied.id()), "query", 1, false));
            assertDenied(() -> service.context(denied.id(), "query", 100, Set.of(), Map.of(), false));
            assertDenied(() -> service.contextAcrossProjects(
                    List.of(denied.id()), "query", 100, Set.of(), Map.of(), false));
        } finally {
            restoreProperty(NexusRestProjectRootPolicy.ROOTS_PROPERTY, previousRoots);
            meterRegistry.close();
            application.close();
        }
    }

    private static void assertDenied(Executable operation) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, operation);
        assertTrue(
                error.getMessage().contains("hors des racines autorisées")
                        || error.getMessage().contains("n'est plus accessible"),
                error::getMessage);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
