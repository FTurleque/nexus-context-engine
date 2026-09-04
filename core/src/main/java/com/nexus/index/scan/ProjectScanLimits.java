package com.nexus.index.scan;

import java.util.Map;
import java.util.Objects;

/**
 * Budget global du walk d'indexation, indépendant de la limite par fichier.
 *
 * <p>{@code maxFiles} conserve son nom historique pour compatibilité de configuration,
 * mais borne désormais toutes les entrées non racine rencontrées par le walk : fichiers,
 * répertoires et entrées ensuite ignorées. Le coût de traversée reste ainsi borné face à
 * un repository contenant des millions de petits fichiers, de répertoires vides ou une
 * arborescence artificiellement profonde. {@code maxTotalBytes} borne le volume cumulé
 * des sources supportées et sûres qui entrent effectivement dans le pipeline de
 * hash/analyse.</p>
 */
public record ProjectScanLimits(int maxFiles, long maxTotalBytes) {

    public static final String MAX_FILES_ENVIRONMENT_VARIABLE = "NEXUS_MAX_INDEX_FILES";
    public static final String MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE = "NEXUS_MAX_INDEX_TOTAL_BYTES";

    public static final int DEFAULT_MAX_FILES = 100_000;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;

    /** Hard safety ceilings: environment configuration may tighten limits, never disable them. */
    public static final int HARD_MAX_FILES = 1_000_000;
    public static final long HARD_MAX_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L;

    public ProjectScanLimits {
        if (maxFiles <= 0 || maxFiles > HARD_MAX_FILES) {
            throw new IllegalArgumentException(
                    "maxFiles must be between 1 and " + HARD_MAX_FILES);
        }
        if (maxTotalBytes <= 0 || maxTotalBytes > HARD_MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException(
                    "maxTotalBytes must be between 1 and " + HARD_MAX_TOTAL_BYTES);
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
                        DEFAULT_MAX_FILES,
                        HARD_MAX_FILES),
                positiveLong(
                        environment.get(MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE),
                        MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE,
                        DEFAULT_MAX_TOTAL_BYTES,
                        HARD_MAX_TOTAL_BYTES));
    }

    private static int positiveInt(String configured, String name, int defaultValue, int maximum) {
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            if (value <= 0 || value > maximum) {
                throw new IllegalArgumentException(name + " doit être compris entre 1 et " + maximum);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier compris entre 1 et " + maximum, exception);
        }
    }

    private static long positiveLong(String configured, String name, long defaultValue, long maximum) {
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(configured.trim());
            if (value <= 0 || value > maximum) {
                throw new IllegalArgumentException(name + " doit être compris entre 1 et " + maximum + " octets");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " doit être un entier compris entre 1 et " + maximum + " octets", exception);
        }
    }
}
