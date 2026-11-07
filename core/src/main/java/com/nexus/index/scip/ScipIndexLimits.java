package com.nexus.index.scip;

import java.util.Map;
import java.util.Objects;

/** Resource limits dedicated to repository-wide SCIP artifacts. */
public record ScipIndexLimits(long maxIndexBytes, int maxMessageBytes) {

    static final String MAX_INDEX_BYTES_ENV = "NEXUS_MAX_SCIP_INDEX_BYTES";
    static final String MAX_MESSAGE_BYTES_ENV = "NEXUS_MAX_SCIP_MESSAGE_BYTES";

    static final long DEFAULT_MAX_INDEX_BYTES = 256L * 1024L * 1024L;
    static final int DEFAULT_MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    // These are security ceilings, not tuning defaults. Environment variables may
    // lower them for constrained runtimes but can no longer enlarge the parser's
    // maximum attack surface beyond the qualified production envelope.
    static final long MAX_CONFIGURABLE_INDEX_BYTES = DEFAULT_MAX_INDEX_BYTES;
    static final int MAX_CONFIGURABLE_MESSAGE_BYTES = DEFAULT_MAX_MESSAGE_BYTES;

    public ScipIndexLimits {
        if (maxIndexBytes <= 0 || maxIndexBytes > MAX_CONFIGURABLE_INDEX_BYTES) {
            throw new IllegalArgumentException(
                    MAX_INDEX_BYTES_ENV + " doit être compris entre 1 et " + MAX_CONFIGURABLE_INDEX_BYTES + " octets");
        }
        if (maxMessageBytes <= 0 || maxMessageBytes > MAX_CONFIGURABLE_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    MAX_MESSAGE_BYTES_ENV + " doit être compris entre 1 et " + MAX_CONFIGURABLE_MESSAGE_BYTES
                            + " octets");
        }
    }

    public static ScipIndexLimits defaults() {
        return new ScipIndexLimits(DEFAULT_MAX_INDEX_BYTES, DEFAULT_MAX_MESSAGE_BYTES);
    }

    /** Résout les bornes depuis l'environnement fourni par la composition root. */
    public static ScipIndexLimits from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new ScipIndexLimits(
                boundedPositiveLong(environment, MAX_INDEX_BYTES_ENV, DEFAULT_MAX_INDEX_BYTES,
                        MAX_CONFIGURABLE_INDEX_BYTES),
                Math.toIntExact(boundedPositiveLong(
                        environment,
                        MAX_MESSAGE_BYTES_ENV,
                        DEFAULT_MAX_MESSAGE_BYTES,
                        MAX_CONFIGURABLE_MESSAGE_BYTES)));
    }

    private static long boundedPositiveLong(
            Map<String, String> environment, String name, long defaultValue, long maximum) {
        String configured = environment.get(name);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        return parseBoundedPositiveLong(name, configured, maximum);
    }

    private static long parseBoundedPositiveLong(String name, String configured, long maximum) {
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
