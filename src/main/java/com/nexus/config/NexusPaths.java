package com.nexus.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Centralise les emplacements de données locales utilisés par NEXUS.
 */
public record NexusPaths(Path home) {

    public static final String HOME_PROPERTY = "nexus.home";
    public static final String HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME";

    public NexusPaths {
        Objects.requireNonNull(home, "home");
        home = home.toAbsolutePath().normalize();
    }

    public static NexusPaths fromEnvironment() {
        String configuredHome = System.getProperty(HOME_PROPERTY);
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = System.getenv(HOME_ENVIRONMENT_VARIABLE);
        }
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = Path.of(System.getProperty("user.home"), ".nexus").toString();
        }
        return new NexusPaths(Path.of(configuredHome));
    }

    public Path databaseFile() {
        return home.resolve("nexus.db");
    }

    public Path indexesDirectory() {
        return home.resolve("indexes");
    }

    public Path integrationsDirectory() {
        return home.resolve("integrations");
    }

    public Path minosIntegrationDirectory() {
        return integrationsDirectory().resolve("minos");
    }

    /**
     * Conventional opt-in location for the MINOS shaded JAR.
     */
    public Path minosIntegrationJar() {
        return minosIntegrationDirectory().resolve("minos-code-intelligence-all.jar");
    }

    public Path projectLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return indexesDirectory().resolve(projectId.toString()).resolve("lucene");
    }

    public Path projectSemanticLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return indexesDirectory().resolve(projectId.toString()).resolve("semantic-lucene");
    }
}
