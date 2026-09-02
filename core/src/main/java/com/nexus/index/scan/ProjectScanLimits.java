package com.nexus.index.scan;

import java.util.Map;
import java.util.Objects;

/**
 * Budget global du walk d'indexation, indépendant de la limite par fichier.
 *
 * <p>{@code maxFiles} borne le nombre d'entrées fichier non ignorées visitées,
 * y compris les extensions non indexables : le coût de traversée reste ainsi
 * borné face à un repository contenant des millions de petits fichiers.
 * {@code maxTotalBytes} borne le volume cumulé des sources supportées et sûres
 * qui entrent effectivement dans le pipeline de hash/analyse.</p>
 */
public record ProjectScanLimits(int maxFiles, long maxTotalBytes) {

    public static final String MAX_FILES_ENVIRONMENT_VARIABLE = "NEXUS_MAX_INDEX_FILES";
    public static final String MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE = "NEXUS_MAX_INDEX_TOTAL_BYTES";

    public static final int DEFAULT_MAX_FILES = 100_000;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;

    public ProjectScanLimits {
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles must be greater than zero");
        }
        if (maxTotalBytes <= 0) {
            throw new IllegalArgumentException("maxTotalBytes must be greater than zero");
        }
    }

    public static ProjectScanLimits defaults() {
        return new ProjectScanLimits(DEFAULT_MAX_FILES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public static ProjectScanLimits fromEnvironment() {
        return from(System.getenv());
    }

    static ProjectScanLimits from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new ProjectScanLimits(
                positiveInt(
                        environment.get(MAX_FILES_ENVIRONMENT_VARIABLE),
                        MAX_FILES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_FILES),
                positiveLong(
                        environment.get(MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE),
                        MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_TOTAL_BYTES));
    }

    private static int positiveInt(String configured, String name, int defaultValue) {
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            if (value <= 0) {
                throw new IllegalArgumentException(name + " doit être strictement positif");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier positif", exception);
        }
    }

    private static long positiveLong(String configured, String name, long defaultValue) {
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
            throw new IllegalArgumentException(name + " doit être un entier positif en octets", exception);
        }
    }
}
