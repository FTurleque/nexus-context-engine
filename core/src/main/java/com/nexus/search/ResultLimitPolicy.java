package com.nexus.search;

/**
 * Politique commune pour les limites de résultats NEXUS.
 *
 * <p>Les points d'entrée publics (CLI, REST, MCP et façade applicative) restent
 * bornés par {@link #MAX_RESULT_LIMIT}. Les stratégies de recherche peuvent
 * effectuer un overfetch interne limité afin de classer et diversifier les
 * candidats sans exposer cette capacité comme une limite de réponse publique.</p>
 */
public final class ResultLimitPolicy {

    public static final int DEFAULT_RESULT_LIMIT = 10;
    public static final int MAX_RESULT_LIMIT = 500;
    public static final int MAX_INTERNAL_RETRIEVAL_LIMIT = MAX_RESULT_LIMIT * 4;

    private ResultLimitPolicy() {
    }

    public static int validate(int limit) {
        return validateBounded(limit, MAX_RESULT_LIMIT, "limit");
    }

    public static int validateInternalRetrieval(int limit) {
        return validateBounded(limit, MAX_INTERNAL_RETRIEVAL_LIMIT, "internal retrieval limit");
    }

    private static int validateBounded(int limit, int maximum, String label) {
        if (limit <= 0) {
            throw new IllegalArgumentException(label + " must be greater than zero");
        }
        if (limit > maximum) {
            throw new IllegalArgumentException(
                    label + " must not exceed " + maximum + " (requested " + limit + ")");
        }
        return limit;
    }
}
