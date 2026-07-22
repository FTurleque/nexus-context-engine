package com.nexus.context;

import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Requête explicite de construction de contexte sur plusieurs projets NEXUS.
 */
public record FederatedContextRequest(
        List<ProjectDescriptor> projects,
        String query,
        int tokenBudget,
        Set<CandidateType> requestedSources,
        Map<String, String> constraints,
        boolean explain) {

    public FederatedContextRequest {
        Objects.requireNonNull(projects, "projects");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(requestedSources, "requestedSources");
        Objects.requireNonNull(constraints, "constraints");
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("projects must not be empty");
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }
        projects = List.copyOf(projects);
        requestedSources = Set.copyOf(requestedSources);
        constraints = Map.copyOf(constraints);
    }
}
