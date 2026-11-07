package com.nexus.config;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration de sécurité partagée par les primitives et les adaptateurs.
 *
 * <p>Les deux postures de sécurité ({@value #STRICT_PATH_IO_ENV} et
 * {@value NexusPaths#REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE}) valent pour tout le
 * processus : {@code SafeFileIO} et le stockage privé les consultent à chaque ouverture. La
 * composition root les installe par {@link #configure(Map)} depuis l'environnement qu'elle a
 * résolu ; ce paquet ne lit jamais l'environnement du processus lui-même. La propriété JVM
 * correspondante reste prioritaire.</p>
 *
 * <p>Ordre d'initialisation : aucune primitive {@code SafeFileIO} ni {@code PrivateStoragePolicy}
 * ne doit être appelée avant la composition root ({@code NexusApplicationFactory}, ou
 * {@code NexusRestExposureGuard} pour l'adaptateur REST). Tant que {@link #configure(Map)} n'a
 * pas été appelée, seules les propriétés JVM s'appliquent et les postures fixées par
 * l'environnement sont ignorées.</p>
 */
public final class SecurityPolicy {
    public static final String STRICT_PATH_IO_PROPERTY = "nexus.requireStrictPathIo";
    public static final String STRICT_PATH_IO_ENV = "NEXUS_REQUIRE_STRICT_PATH_IO";

    private static volatile Posture posture = Posture.UNSET;

    private SecurityPolicy() { }

    /**
     * Installe les postures de sécurité résolues par la composition root. Seules les deux
     * variables de posture sont retenues ; leur validation est faite à la lecture.
     */
    public static void configure(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        posture = new Posture(
                environment.get(STRICT_PATH_IO_ENV),
                environment.get(NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE));
    }

    public static boolean requireStrictPathIo() {
        return booleanSetting(STRICT_PATH_IO_PROPERTY, STRICT_PATH_IO_ENV, posture.strictPathIo());
    }

    public static boolean requirePrivateStorage() {
        return booleanSetting(
                NexusPaths.REQUIRE_PRIVATE_STORAGE_PROPERTY,
                NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE,
                posture.requirePrivateStorage());
    }

    static boolean booleanSetting(String property, String environment, String configuredValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = configuredValue;
        }
        if (value == null || value.isBlank() || "false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        throw new IllegalStateException(environment + " doit valoir true ou false");
    }

    public static void requireHardenedStorageAndTraversal() {
        if (!requirePrivateStorage()) {
            throw new IllegalStateException("Une exposition hardened exige "
                    + NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE + "=true");
        }
        if (!requireStrictPathIo()) {
            throw new IllegalStateException("Une exposition hardened exige " + STRICT_PATH_IO_ENV + "=true");
        }
    }

    private record Posture(String strictPathIo, String requirePrivateStorage) {
        private static final Posture UNSET = new Posture(null, null);
    }
}
