package com.nexus.context;

/** Physical I/O limits for task-context materialization. */
public record ContextMaterializationLimits(long maxCumulativeBytes) {

    public static final String MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE =
            "NEXUS_MAX_CONTEXT_MATERIALIZATION_BYTES";
    public static final long DEFAULT_MAX_CUMULATIVE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_CONFIGURABLE_CUMULATIVE_BYTES = 512L * 1024L * 1024L;

    public ContextMaterializationLimits {
        if (maxCumulativeBytes <= 0L || maxCumulativeBytes > MAX_CONFIGURABLE_CUMULATIVE_BYTES) {
            throw new IllegalArgumentException(
                    "maxCumulativeBytes must be between 1 and "
                            + MAX_CONFIGURABLE_CUMULATIVE_BYTES + " bytes");
        }
    }

    public static ContextMaterializationLimits fromEnvironment() {
        String configured = System.getenv(MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return new ContextMaterializationLimits(DEFAULT_MAX_CUMULATIVE_BYTES);
        }
        return new ContextMaterializationLimits(parseMaxCumulativeBytes(configured));
    }

    static long parseMaxCumulativeBytes(String configured) {
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0L || value > MAX_CONFIGURABLE_CUMULATIVE_BYTES) {
                throw new IllegalArgumentException(
                        MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE + " doit être compris entre 1 et "
                                + MAX_CONFIGURABLE_CUMULATIVE_BYTES + " octets");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    MAX_CUMULATIVE_BYTES_ENVIRONMENT_VARIABLE + " doit être un entier en octets",
                    exception);
        }
    }

    public ContextMaterializationBudget newBudget() {
        return new ContextMaterializationBudget(this);
    }
}
