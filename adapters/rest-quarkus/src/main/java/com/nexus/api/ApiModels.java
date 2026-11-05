package com.nexus.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApiModels {

    private ApiModels() {
    }

    public record CreateProjectRequest(String rootPath, String name) {
    }

    public record SearchRequest(String query, Integer limit, Boolean explain) {
    }

    public record FederatedSearchRequest(
            List<UUID> projectIds,
            String query,
            Integer limit,
            Boolean explain) {
    }

    public record ContextRequestDto(
            String query,
            Integer tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            Boolean explain) {
    }

    public record FederatedContextRequest(
            List<UUID> projectIds,
            String query,
            Integer tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            Boolean explain) {
    }

    public record ErrorResponse(String error, String message) {
    }
}
