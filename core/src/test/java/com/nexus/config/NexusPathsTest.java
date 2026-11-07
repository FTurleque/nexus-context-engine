package com.nexus.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NexusPathsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesHomeFromInjectedEnvironmentWhenNoPropertyIsSet() {
        String previous = System.getProperty(NexusPaths.HOME_PROPERTY);
        try {
            System.clearProperty(NexusPaths.HOME_PROPERTY);
            Path configured = temporaryDirectory.resolve("from-env");

            NexusPaths paths = NexusPaths.fromEnvironment(
                    Map.of(NexusPaths.HOME_ENVIRONMENT_VARIABLE, configured.toString()));

            assertEquals(configured.toAbsolutePath().normalize(), paths.home());
        } finally {
            restore(NexusPaths.HOME_PROPERTY, previous);
        }
    }

    @Test
    void homePropertyOverridesInjectedEnvironment() {
        String previous = System.getProperty(NexusPaths.HOME_PROPERTY);
        try {
            Path property = temporaryDirectory.resolve("from-property");
            System.setProperty(NexusPaths.HOME_PROPERTY, property.toString());

            NexusPaths paths = NexusPaths.fromEnvironment(
                    Map.of(NexusPaths.HOME_ENVIRONMENT_VARIABLE, temporaryDirectory.resolve("ignored").toString()));

            assertEquals(property.toAbsolutePath().normalize(), paths.home());
        } finally {
            restore(NexusPaths.HOME_PROPERTY, previous);
        }
    }

    @Test
    void fallsBackToUserHomeWhenNothingIsConfigured() {
        String previous = System.getProperty(NexusPaths.HOME_PROPERTY);
        try {
            System.clearProperty(NexusPaths.HOME_PROPERTY);

            NexusPaths paths = NexusPaths.fromEnvironment(Map.of(NexusPaths.HOME_ENVIRONMENT_VARIABLE, " "));

            assertEquals(Path.of(System.getProperty("user.home"), ".nexus").toAbsolutePath().normalize(), paths.home());
        } finally {
            restore(NexusPaths.HOME_PROPERTY, previous);
        }
    }

    @Test
    void derivesPersistentLocationsFromHome() {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("home"));
        UUID projectId = UUID.randomUUID();

        assertEquals(paths.home().resolve("nexus.db"), paths.databaseFile());
        assertEquals(paths.home().resolve("locks").resolve(projectId + ".index.lock"), paths.projectIndexLock(projectId));
        assertEquals(paths.home().resolve("indexes").resolve(projectId.toString()).resolve("lucene"),
                paths.projectLuceneIndex(projectId));
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
