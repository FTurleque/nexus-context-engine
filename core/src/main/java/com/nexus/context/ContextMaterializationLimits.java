package com.nexus.context;

import java.util.Map;
import java.util.Objects;

/** Physical I/O limits for task-context materialization. */
public record ContextMaterializationLimits(
        long maxCumulativeBytes,
        int maxOpenedFiles,
        long maxDurationMillis) implements com.nexus.security.PublicCounterSnapshot {

    public static final String MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE =
            "NEXUS_MAX_CONTEXT_MATERIALIZATION_BYTES";
    public static final String MAX_OPENED_FILES_ENVIRONMENT_VARIABLE =
            "NEXUS_MAX_CONTEXT_MATERIALIZATION_FILES";
    public static final String MAX_DURATION_MILLIS_ENVIRONMENT_VARIABLE =
            "NEXUS_MAX_CONTEXT_MATERIALIZATION_MILLIS";

    public static final long DEFAULT_MAX_CUMULATIVE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_CONFIGURABLE_CUMULATIVE_BYTES = 512L * 1024L * 1024L;
    public static final int DEFAULT_MAX_OPENED_FILES = 10_000;
    public static final int MAX_CONFIGURABLE_OPENED_FILES = 100_000;
    public static final long DEFAULT_MAX_DURATION_MILLIS = 30_000L;
    public static final long MAX_CONFIGURABLE_DURATION_MILLIS = 300_000L;

    /** Compatibility constructor preserving the historical byte-only API. */
    public ContextMaterializationLimits(long maxCumulativeBytes) {
        this(maxCumulativeBytes, DEFAULT_MAX_OPENED_FILES, DEFAULT_MAX_DURATION_MILLIS);
    }

    public ContextMaterializationLimits {
        if (maxCumulativeBytes <= 0L || maxCumulativeBytes > MAX_CONFIGURABLE_CUMULATIVE_BYTES) {
            throw new IllegalArgumentException(
                    "maxCumulativeBytes doit être compris entre 1 et "
                            + MAX_CONFIGURABLE_CUMULATIVE_BYTES + " octets");
        }
        if (maxOpenedFiles <= 0 || maxOpenedFiles > MAX_CONFIGURABLE_OPENED_FILES) {
            throw new IllegalArgumentException(
                    "maxOpenedFiles doit être compris entre 1 et " + MAX_CONFIGURABLE_OPENED_FILES);
        }
        if (maxDurationMillis <= 0L || maxDurationMillis > MAX_CONFIGURABLE_DURATION_MILLIS) {
            throw new IllegalArgumentException(
                    "maxDurationMillis doit être compris entre 1 et "
                            + MAX_CONFIGURABLE_DURATION_MILLIS + " milliseconds");
        }
    }

    public static ContextMaterializationLimits defaults() {
        return new ContextMaterializationLimits(
                DEFAULT_MAX_CUMULATIVE_BYTES, DEFAULT_MAX_OPENED_FILES, DEFAULT_MAX_DURATION_MILLIS);
    }

    /** Résout les bornes depuis l'environnement fourni par la composition root. */
    public static ContextMaterializationLimits from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new ContextMaterializationLimits(
                configuredLong(
                        environment,
                        MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_CUMULATIVE_BYTES,
                        MAX_CONFIGURABLE_CUMULATIVE_BYTES,
                        "octets"),
                (int) configuredLong(
                        environment,
                        MAX_OPENED_FILES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_OPENED_FILES,
                        MAX_CONFIGURABLE_OPENED_FILES,
                        "fichiers"),
                configuredLong(
                        environment,
                        MAX_DURATION_MILLIS_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_DURATION_MILLIS,
                        MAX_CONFIGURABLE_DURATION_MILLIS,
                        "millisecondes"));
    }

    private static long configuredLong(
            Map<String, String> environment, String name, long defaultValue, long maximum, String unit) {
        String configured = environment.get(name);
        return configured == null || configured.isBlank()
                ? defaultValue
                : parseBoundedLong(name, configured, maximum, unit);
    }

    private static long parseBoundedLong(String name, String configured, long maximum, String unit) {
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0L || value > maximum) {
                throw new IllegalArgumentException(
                        name + " doit être compris entre 1 et " + maximum + " " + unit);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier en " + unit, exception);
        }
    }

    public ContextMaterializationBudget newBudget() {
        return new ContextMaterializationBudget(this);
    }
}
