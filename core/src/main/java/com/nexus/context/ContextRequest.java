package com.nexus.context;

import com.nexus.search.CandidateType;

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

    public ContextRequest {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(requestedSources, "requestedSources");
        Objects.requireNonNull(constraints, "constraints");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        ContextBudgetPolicy.validate(tokenBudget);
        requestedSources = Set.copyOf(requestedSources);
        constraints = Map.copyOf(constraints);
        if (!constraints.isEmpty()) {
            throw new IllegalArgumentException(
                    "constraints are not supported yet; omit the field or provide an empty object");
        }
    }
}
