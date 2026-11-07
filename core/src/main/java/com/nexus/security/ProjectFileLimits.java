package com.nexus.security;

import java.util.Map;
import java.util.Objects;

/**
 * Politique commune de taille maximale pour les fichiers de repository lus par NEXUS.
 *
 * <p>La valeur se résout depuis l'environnement fourni par la composition root
 * ({@link #maxFileSize(Map)}). Les lectures sans borne explicite de {@link SafeFileIO} et de
 * {@code FileHasher} consultent la valeur installée pour le processus par
 * {@link #configure(long)} ; sans installation, elles appliquent
 * {@link #DEFAULT_MAX_FILE_SIZE_BYTES}.</p>
 */
public final class ProjectFileLimits {

    public static final String MAX_FILE_SIZE_ENVIRONMENT_VARIABLE = "NEXUS_MAX_FILE_SIZE_BYTES";
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 8L * 1024L * 1024L;
    public static final long MAX_CONFIGURABLE_FILE_SIZE_BYTES = 256L * 1024L * 1024L;

    private static volatile long configuredMaxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;

    private ProjectFileLimits() {
    }

    /** Résout {@value #MAX_FILE_SIZE_ENVIRONMENT_VARIABLE} depuis l'environnement fourni. */
    public static long maxFileSize(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(MAX_FILE_SIZE_ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_FILE_SIZE_BYTES;
        }
        return parseMaxFileSize(configured);
    }

    /** Installe la borne résolue par la composition root pour les lectures sans borne explicite. */
    public static void configure(long maxFileSizeBytes) {
        if (maxFileSizeBytes <= 0 || maxFileSizeBytes > MAX_CONFIGURABLE_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être compris entre 1 et "
                            + MAX_CONFIGURABLE_FILE_SIZE_BYTES + " octets");
        }
        configuredMaxFileSizeBytes = maxFileSizeBytes;
    }

    /** Borne installée pour le processus, ou la valeur par défaut. */
    public static long configuredMaxFileSize() {
        return configuredMaxFileSizeBytes;
    }

    private static long parseMaxFileSize(String configured) {
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0 || value > MAX_CONFIGURABLE_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException(
                        MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être compris entre 1 et "
                                + MAX_CONFIGURABLE_FILE_SIZE_BYTES + " octets");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être un entier en octets", exception);
        }
    }
}
