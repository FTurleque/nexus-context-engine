package com.nexus.index.scip;

/** Resource limits dedicated to repository-wide SCIP artifacts. */
final class ScipIndexLimits {

    static final String MAX_INDEX_BYTES_ENV = "NEXUS_MAX_SCIP_INDEX_BYTES";
    static final String MAX_MESSAGE_BYTES_ENV = "NEXUS_MAX_SCIP_MESSAGE_BYTES";

    static final long DEFAULT_MAX_INDEX_BYTES = 256L * 1024L * 1024L;
    static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    static final long MAX_CONFIGURABLE_INDEX_BYTES = 1024L * 1024L * 1024L;
    static final int MAX_CONFIGURABLE_MESSAGE_BYTES = 64 * 1024 * 1024;

    private ScipIndexLimits() {
    }

    static long maxIndexBytesFromEnvironment() {
        return boundedPositiveLong(MAX_INDEX_BYTES_ENV, DEFAULT_MAX_INDEX_BYTES, MAX_CONFIGURABLE_INDEX_BYTES);
    }

    static int maxMessageBytesFromEnvironment() {
        return Math.toIntExact(boundedPositiveLong(
                MAX_MESSAGE_BYTES_ENV,
                DEFAULT_MAX_MESSAGE_BYTES,
                MAX_CONFIGURABLE_MESSAGE_BYTES));
    }

    static long boundedPositiveLong(String name, long defaultValue, long maximum) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        return parseBoundedPositiveLong(name, configured, maximum);
    }

    static long parseBoundedPositiveLong(String name, String configured, long maximum) {
        if (maximum <= 0) {
            throw new IllegalArgumentException("maximum must be greater than zero");
        }
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0 || value > maximum) {
                throw new IllegalArgumentException(
                        name + " doit être compris entre 1 et " + maximum + " octets");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier en octets", exception);
        }
    }
}
