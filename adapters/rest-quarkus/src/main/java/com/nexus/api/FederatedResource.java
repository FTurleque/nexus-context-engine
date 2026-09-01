package com.nexus.api;

import com.nexus.api.ApiModels.FederatedContextRequest;
import com.nexus.api.ApiModels.FederatedContextResponse;
import com.nexus.api.ApiModels.FederatedSearchRequest;
import com.nexus.api.ApiModels.FederatedSearchResponse;
import com.nexus.context.ContextBudgetPolicy;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.search.QueryPolicy;
import com.nexus.search.ResultLimitPolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/v1/federated")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FederatedResource {

    private static final int DEFAULT_SEARCH_LIMIT = ResultLimitPolicy.DEFAULT_RESULT_LIMIT;
    private static final int DEFAULT_TOKEN_BUDGET = ContextBudgetPolicy.DEFAULT_CONTEXT_TOKEN_BUDGET;

    @Inject
    NexusApiApplicationService service;

    @POST
    @Path("/search")
    public FederatedSearchResponse search(FederatedSearchRequest request) throws IOException {
        requireRequest(request);
        List<UUID> projectIds = requireProjects(request.projectIds());
        String query = QueryPolicy.normalize(request.query());
        int limit = request.limit() == null ? DEFAULT_SEARCH_LIMIT : ResultLimitPolicy.validate(request.limit());
        boolean explain = Boolean.TRUE.equals(request.explain());
        return ApiMapper.federatedSearch(service.searchAcrossProjects(projectIds, query, limit, explain));
    }

    @POST
    @Path("/context")
    public FederatedContextResponse context(FederatedContextRequest request) {
        requireRequest(request);
        List<UUID> projectIds = requireProjects(request.projectIds());
        String query = QueryPolicy.normalize(request.query());
        int tokenBudget = request.tokenBudget() == null
                ? DEFAULT_TOKEN_BUDGET
                : ContextBudgetPolicy.validate(request.tokenBudget());
        Set<String> requestedSources = request.requestedSources() == null ? Set.of() : request.requestedSources();
        Map<String, String> constraints = request.constraints() == null ? Map.of() : request.constraints();
        boolean explain = Boolean.TRUE.equals(request.explain());
        return ApiMapper.federatedContext(service.contextAcrossProjects(
                projectIds, query, tokenBudget, requestedSources, constraints, explain));
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw new IllegalArgumentException("Le corps de la requête est obligatoire");
        }
    }

    private static List<UUID> requireProjects(List<UUID> projectIds) {
        return FederatedScopePolicy.normalizeProjectIds(projectIds);
    }
}
