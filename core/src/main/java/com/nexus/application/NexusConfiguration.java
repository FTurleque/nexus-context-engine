package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.config.SecurityPolicy;
import com.nexus.context.ContextMaterializationLimits;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider;
import com.nexus.index.scan.ProjectScanLimits;
import com.nexus.project.FederationDeadlinePolicy;
import com.nexus.security.ProjectFileLimits;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Configuration {@code NEXUS_*} effective du cœur, résolue une fois par la composition root.
 *
 * <p>{@code NexusApplicationFactory.configuration(NexusPaths, Map)} la construit depuis une
 * {@code Map<String, String>} : l'environnement du processus en production, une table en test.
 * Chaque composant reçoit sa part par constructeur ; aucune classe du cœur ne relit
 * l'environnement. Les noms de variables, les bornes, les valeurs par défaut et les messages
 * d'erreur restent ceux des classes de limites, qui portent chacune leur fabrique
 * {@code from(Map)}. Les bornes SCIP sont portées en valeurs simples : le format SCIP reste
 * confiné à son paquetage et à la composition (ARCH-0080).</p>
 *
 * <p>Deux parts valent pour tout le processus parce que des primitives statiques les
 * consultent : la borne de taille de fichier des lectures sans borne explicite
 * ({@link ProjectFileLimits}) et les postures {@code NEXUS_REQUIRE_STRICT_PATH_IO} et
 * {@code NEXUS_REQUIRE_PRIVATE_STORAGE} ({@link SecurityPolicy}). La composition root les
 * installe par {@link #installProcessPolicies()} avant d'ouvrir le stockage.</p>
 *
 * <p>La configuration sémantique ({@code NEXUS_SEMANTIC_*}) reste un paramètre distinct des
 * fabriques de {@link NexusApplication}, qui permettent de la fournir explicitement.</p>
 */
public record NexusConfiguration(
        long maxFileSizeBytes,
        ProjectScanLimits projectScanLimits,
        ContextMaterializationLimits contextMaterializationLimits,
        ContextDiscoveryLimits contextDiscoveryLimits,
        FederationDeadlinePolicy federationDeadlinePolicy,
        long maxScipIndexBytes,
        int maxScipMessageBytes,
        int maxConcurrentIndexing,
        Duration codeIntelligenceTimeout,
        Optional<JdtLanguageServerCodeIntelligenceProvider.Configuration> jdtConfiguration,
        Map<String, String> securityPosture) {

    public NexusConfiguration {
        Objects.requireNonNull(projectScanLimits, "projectScanLimits");
        Objects.requireNonNull(contextMaterializationLimits, "contextMaterializationLimits");
        Objects.requireNonNull(contextDiscoveryLimits, "contextDiscoveryLimits");
        Objects.requireNonNull(federationDeadlinePolicy, "federationDeadlinePolicy");
        Objects.requireNonNull(codeIntelligenceTimeout, "codeIntelligenceTimeout");
        Objects.requireNonNull(jdtConfiguration, "jdtConfiguration");
        securityPosture = Map.copyOf(Objects.requireNonNull(securityPosture, "securityPosture"));
    }

    /**
     * Résout {@code NEXUS_HOME} depuis l'environnement du processus, pour les points d'entrée
     * (CLI, MCP, REST) qui composent ensuite {@link NexusApplication}.
     */
    public static NexusPaths pathsFromEnvironment() {
        return NexusPaths.fromEnvironment(NexusApplicationFactory.processEnvironment());
    }

    /** Installe les parts de configuration qui valent pour tout le processus. */
    void installProcessPolicies() {
        ProjectFileLimits.configure(maxFileSizeBytes);
        SecurityPolicy.configure(securityPosture);
    }

    /** Retient les seules variables de posture de sécurité présentes dans {@code environment}. */
    static Map<String, String> securityPosture(Map<String, String> environment) {
        Map<String, String> posture = new TreeMap<>();
        for (String name : new String[] {
                SecurityPolicy.STRICT_PATH_IO_ENV, NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE}) {
            String value = environment.get(name);
            if (value != null) {
                posture.put(name, value);
            }
        }
        return posture;
    }
}
