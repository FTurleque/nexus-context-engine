package com.nexus.context;

import com.nexus.search.candidate.CandidateType;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ContextRequest(
        UUID projectId,
        String query,
        int tokenBudget,
        Set<CandidateType> requestedSources,
        Map<String, String> constraints,
        boolean explain) {

    /** Message public du refus d'une map {@code constraints} non vide, repris tel quel par REST. */
    public static final String UNSUPPORTED_CONSTRAINTS_MESSAGE =
            "Les contraintes ne sont pas encore prises en charge ; omettez le champ ou fournissez un objet vide";

    public ContextRequest {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(requestedSources, "requestedSources");
        Objects.requireNonNull(constraints, "constraints");
        if (query.isBlank()) {
            throw new IllegalArgumentException("La requête ne peut pas être vide");
        }
        ContextBudgetPolicy.validate(tokenBudget);
        requestedSources = Set.copyOf(requestedSources);
        constraints = Map.copyOf(constraints);
        if (!constraints.isEmpty()) {
            throw new IllegalArgumentException(UNSUPPORTED_CONSTRAINTS_MESSAGE);
        }
    }
}
