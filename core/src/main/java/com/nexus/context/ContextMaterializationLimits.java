package com.nexus.context;

/** Physical I/O limits for task-context materialization. */
public record ContextMaterializationLimits(
        long maxCumulativeBytes,
        int maxOpenedFiles,
        long maxDurationMillis) {

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
                    "maxCumulativeBytes must be between 1 and "
                            + MAX_CONFIGURABLE_CUMULATIVE_BYTES + " bytes");
        }
        if (maxOpenedFiles <= 0 || maxOpenedFiles > MAX_CONFIGURABLE_OPENED_FILES) {
            throw new IllegalArgumentException(
                    "maxOpenedFiles must be between 1 and " + MAX_CONFIGURABLE_OPENED_FILES);
        }
        if (maxDurationMillis <= 0L || maxDurationMillis > MAX_CONFIGURABLE_DURATION_MILLIS) {
            throw new IllegalArgumentException(
                    "maxDurationMillis must be between 1 and "
                            + MAX_CONFIGURABLE_DURATION_MILLIS + " milliseconds");
        }
    }

    public static ContextMaterializationLimits fromEnvironment() {
        return new ContextMaterializationLimits(
                configuredLong(
                        MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_CUMULATIVE_BYTES,
                        MAX_CONFIGURABLE_CUMULATIVE_BYTES,
                        "octets"),
                configuredInt(
                        MAX_OPENED_FILES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_OPENED_FILES,
                        MAX_CONFIGURABLE_OPENED_FILES,
                        "fichiers"),
                configuredLong(
                        MAX_DURATION_MILLIS_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_DURATION_MILLIS,
                        MAX_CONFIGURABLE_DURATION_MILLIS,
                        "millisecondes"));
    }

    static long parseMaxCumulativeBytes(String configured) {
        return parseBoundedLong(
                MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE,
                configured,
                MAX_CONFIGURABLE_CUMULATIVE_BYTES,
                "octets");
    }

    static int parseMaxOpenedFiles(String configured) {
        long value = parseBoundedLong(
                MAX_OPENED_FILES_ENVIRONMENT_VARIABLE,
                configured,
                MAX_CONFIGURABLE_OPENED_FILES,
                "fichiers");
        return (int) value;
    }

    static long parseMaxDurationMillis(String configured) {
        return parseBoundedLong(
                MAX_DURATION_MILLIS_ENVIRONMENT_VARIABLE,
                configured,
                MAX_CONFIGURABLE_DURATION_MILLIS,
                "millisecondes");
    }

    private static long configuredLong(String name, long defaultValue, long maximum, String unit) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank()
                ? defaultValue
                : parseBoundedLong(name, configured, maximum, unit);
    }

    private static int configuredInt(String name, int defaultValue, int maximum, String unit) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank()
                ? defaultValue
                : (int) parseBoundedLong(name, configured, maximum, unit);
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
