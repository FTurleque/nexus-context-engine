package com.nexus.search;

/**
 * Politique applicative commune pour les limites de résultats exposées par NEXUS.
 *
 * <p>Les points d'entrée CLI, REST et MCP doivent appliquer exactement la même
 * borne afin qu'une valeur très grande ne puisse jamais être transmise jusqu'à
 * SQLite, Lucene ou au ranking. Une valeur hors contrat est rejetée explicitement
 * plutôt que tronquée silencieusement.</p>
 */
public final class ResultLimitPolicy {

    public static final int DEFAULT_RESULT_LIMIT = 10;
    public static final int MAX_RESULT_LIMIT = 500;

    private ResultLimitPolicy() {
    }

    public static int validate(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        if (limit > MAX_RESULT_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must not exceed " + MAX_RESULT_LIMIT + " (requested " + limit + ")");
        }
        return limit;
    }
}
