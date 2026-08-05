package com.nexus.security;

/** Politique commune de taille maximale pour les fichiers de repository lus par NEXUS. */
public final class ProjectFileLimits {

    public static final String MAX_FILE_SIZE_ENVIRONMENT_VARIABLE = "NEXUS_MAX_FILE_SIZE_BYTES";
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 8L * 1024L * 1024L;

    private ProjectFileLimits() {
    }

    public static long maxFileSizeFromEnvironment() {
        String configured = System.getenv(MAX_FILE_SIZE_ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_FILE_SIZE_BYTES;
        }
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(
                        MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être strictement positif");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être un entier en octets", exception);
        }
    }
}
