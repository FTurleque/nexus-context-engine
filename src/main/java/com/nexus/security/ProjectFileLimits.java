package com.nexus.security;

/** Politique commune de taille maximale pour les fichiers de repository lus par NEXUS. */
public final class ProjectFileLimits {

    public static final String MAX_FILE_SIZE_ENVIRONMENT_VARIABLE = "NEXUS_MAX_FILE_SIZE_BYTES";
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 8L * 1024L * 1024L;
    public static final long MAX_CONFIGURABLE_FILE_SIZE_BYTES = 256L * 1024L * 1024L;

    private ProjectFileLimits() {
    }

    public static long maxFileSizeFromEnvironment() {
        String configured = System.getenv(MAX_FILE_SIZE_ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_FILE_SIZE_BYTES;
        }
        return parseMaxFileSize(configured);
    }

    static long parseMaxFileSize(String configured) {
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
