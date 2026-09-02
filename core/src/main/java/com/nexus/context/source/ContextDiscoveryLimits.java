package com.nexus.context.source;

import java.util.Map;
import java.util.Objects;

/**
 * Global work limits applied before native context discovery performs expensive
 * filesystem or Git operations.
 */
public record ContextDiscoveryLimits(
        int maxVisitedEntries,
        int maxCandidateResources,
        long maxCumulativeBytes,
        long maxElapsedMillis) {

    public static final String ENV_MAX_VISITED_ENTRIES = "NEXUS_CONTEXT_DISCOVERY_MAX_VISITED_ENTRIES";
    public static final String ENV_MAX_CANDIDATES = "NEXUS_CONTEXT_DISCOVERY_MAX_CANDIDATES";
    public static final String ENV_MAX_BYTES = "NEXUS_CONTEXT_DISCOVERY_MAX_BYTES";
    public static final String ENV_MAX_MILLIS = "NEXUS_CONTEXT_DISCOVERY_MAX_MILLIS";

    private static final int DEFAULT_MAX_VISITED_ENTRIES = 100_000;
    private static final int DEFAULT_MAX_CANDIDATE_RESOURCES = 5_000;
    private static final long DEFAULT_MAX_CUMULATIVE_BYTES = 32L * 1024L * 1024L;
    private static final long DEFAULT_MAX_ELAPSED_MILLIS = 15_000L;

    private static final int HARD_MAX_VISITED_ENTRIES = 1_000_000;
    private static final int HARD_MAX_CANDIDATE_RESOURCES = 100_000;
    private static final long HARD_MAX_CUMULATIVE_BYTES = 512L * 1024L * 1024L;
    private static final long HARD_MAX_ELAPSED_MILLIS = 120_000L;

    public ContextDiscoveryLimits {
        requireRange("maxVisitedEntries", maxVisitedEntries, 1L, HARD_MAX_VISITED_ENTRIES);
        requireRange("maxCandidateResources", maxCandidateResources, 1L, HARD_MAX_CANDIDATE_RESOURCES);
        requireRange("maxCumulativeBytes", maxCumulativeBytes, 1L, HARD_MAX_CUMULATIVE_BYTES);
        requireRange("maxElapsedMillis", maxElapsedMillis, 1L, HARD_MAX_ELAPSED_MILLIS);
    }

    public static ContextDiscoveryLimits defaults() {
        return new ContextDiscoveryLimits(
                DEFAULT_MAX_VISITED_ENTRIES,
                DEFAULT_MAX_CANDIDATE_RESOURCES,
                DEFAULT_MAX_CUMULATIVE_BYTES,
                DEFAULT_MAX_ELAPSED_MILLIS);
    }

    public static ContextDiscoveryLimits fromEnvironment() {
        return from(System.getenv());
    }

    public static ContextDiscoveryLimits from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new ContextDiscoveryLimits(
                parseInt(environment, ENV_MAX_VISITED_ENTRIES, DEFAULT_MAX_VISITED_ENTRIES),
                parseInt(environment, ENV_MAX_CANDIDATES, DEFAULT_MAX_CANDIDATE_RESOURCES),
                parseLong(environment, ENV_MAX_BYTES, DEFAULT_MAX_CUMULATIVE_BYTES),
                parseLong(environment, ENV_MAX_MILLIS, DEFAULT_MAX_ELAPSED_MILLIS));
    }

    public ContextDiscoveryBudget newBudget() {
        return new ContextDiscoveryBudget(this);
    }

    private static int parseInt(Map<String, String> environment, String key, int defaultValue) {
        long parsed = parse(environment, key, defaultValue);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " dépasse la capacité entière supportée: " + parsed);
        }
        return (int) parsed;
    }

    private static long parseLong(Map<String, String> environment, String key, long defaultValue) {
        return parse(environment, key, defaultValue);
    }

    private static long parse(Map<String, String> environment, String key, long defaultValue) {
        String raw = environment.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " doit être un entier strictement positif: " + raw, invalid);
        }
    }

    private static void requireRange(String name, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " doit être compris entre " + minimum + " et " + maximum + " (reçu " + value + ")");
        }
    }
}
