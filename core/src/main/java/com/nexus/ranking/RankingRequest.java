package com.nexus.ranking;

import java.util.Objects;

public record RankingRequest(String query, int limit, boolean explain) {

    public RankingRequest {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("La requête ne peut pas être vide");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit doit être strictement positif");
        }
    }
}
