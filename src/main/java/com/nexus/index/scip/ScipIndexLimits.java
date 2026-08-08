package com.nexus.index.scip;

/** Resource limits dedicated to repository-wide SCIP artifacts. */
final class ScipIndexLimits {

    static final String MAX_INDEX_BYTES_ENV = "NEXUS_MAX_SCIP_INDEX_BYTES";
    static final String MAX_MESSAGE_BYTES_ENV = "NEXUS_MAX_SCIP_MESSAGE_BYTES";

    static final long DEFAULT_MAX_INDEX_BYTES = 256L * 1024L * 1024L;
    static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private ScipIndexLimits() {
    }

    static long maxIndexBytesFromEnvironment() {
        return positiveLong(MAX_INDEX_BYTES_ENV, DEFAULT_MAX_INDEX_BYTES);
    }

    static int maxMessageBytesFromEnvironment() {
        long value = positiveLong(MAX_MESSAGE_BYTES_ENV, DEFAULT_MAX_MESSAGE_BYTES);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    MAX_MESSAGE_BYTES_ENV + " doit être inférieur ou égal à " + Integer.MAX_VALUE);
        }
        return (int) value;
    }

    private static long positiveLong(String name, long defaultValue) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(name + " doit être strictement positif");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier en octets", exception);
        }
    }
}
