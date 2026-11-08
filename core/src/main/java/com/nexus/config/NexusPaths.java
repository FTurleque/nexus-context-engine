package com.nexus.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Centralise les emplacements de données locales utilisés par NEXUS.
 *
 * <p>Ce record ne fait que résoudre des chemins. Le durcissement POSIX/ACL du stockage vit dans
 * {@code com.nexus.security.PrivateStoragePolicy}.</p>
 */
public record NexusPaths(Path home) {

    private static final String PROJECT_ID_ARGUMENT = "projectId";

    public static final String HOME_PROPERTY = "nexus.home";
    public static final String HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME";
    public static final String REQUIRE_PRIVATE_STORAGE_PROPERTY = "nexus.requirePrivateStorage";
    public static final String REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE = "NEXUS_REQUIRE_PRIVATE_STORAGE";

    public NexusPaths {
        Objects.requireNonNull(home, "home");
        home = home.toAbsolutePath().normalize();
    }

    /**
     * Résout {@code NEXUS_HOME} : propriété système {@value #HOME_PROPERTY}, puis variable
     * {@value #HOME_ENVIRONMENT_VARIABLE} de l'environnement fourni par la composition root,
     * puis {@code ${user.home}/.nexus}.
     */
    public static NexusPaths fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configuredHome = System.getProperty(HOME_PROPERTY);
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = environment.get(HOME_ENVIRONMENT_VARIABLE);
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

    public Path locksDirectory() {
        return home.resolve("locks");
    }

    public Path projectIndexLock(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        return locksDirectory().resolve(projectId + ".index.lock");
    }

    public Path projectLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        return indexesDirectory().resolve(projectId.toString()).resolve("lucene");
    }

    public Path projectSemanticLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        return indexesDirectory().resolve(projectId.toString()).resolve("semantic-lucene");
    }
}
