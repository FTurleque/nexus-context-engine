package com.nexus.api;

import com.nexus.api.ApiModels.ContextRequestDto;
import com.nexus.api.ApiModels.ContextResponse;
import com.nexus.api.ApiModels.CreateProjectRequest;
import com.nexus.api.ApiModels.IndexResponse;
import com.nexus.api.ApiModels.IndexStatisticsResponse;
import com.nexus.api.ApiModels.ProjectResponse;
import com.nexus.api.ApiModels.SearchRequest;
import com.nexus.api.ApiModels.SearchResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/v1/projects")
@Produces(MediaType.APPLICATION_JSON)
public class NexusResource {

    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int DEFAULT_TOKEN_BUDGET = 2_000;

    @Inject
    NexusApiApplicationService service;

    @GET
    public List<ProjectResponse> listProjects() {
        return service.listProjects().stream()
                .map(ApiMapper::project)
                .toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createProject(CreateProjectRequest request) throws IOException {
        requireRequest(request, "Le corps de la requête projet est obligatoire");
        String rootPath = requireNonBlank(request.rootPath(), "rootPath est obligatoire");
        ProjectResponse project = ApiMapper.project(service.registerProject(Paths.get(rootPath), request.name()));
        return Response.status(Response.Status.CREATED).entity(project).build();
    }

    @GET
    @Path("/{projectId}")
    public ProjectResponse getProject(@PathParam("projectId") UUID projectId) {
        return ApiMapper.project(service.getProject(projectId));
    }

    @POST
    @Path("/{projectId}/index")
    public IndexResponse index(
            @PathParam("projectId") UUID projectId,
            @QueryParam("rebuild") @DefaultValue("false") boolean rebuild,
            @QueryParam("deepJava") @DefaultValue("false") boolean deepJava) throws IOException {
        return ApiMapper.index(service.index(projectId, rebuild, deepJava));
    }

    @GET
    @Path("/{projectId}/index")
    public IndexStatisticsResponse inspect(@PathParam("projectId") UUID projectId) {
        return ApiMapper.statistics(service.inspect(projectId));
    }

    @POST
    @Path("/{projectId}/search")
    @Consumes(MediaType.APPLICATION_JSON)
    public SearchResponse search(@PathParam("projectId") UUID projectId, SearchRequest request) throws IOException {
        return search(projectId, request, null);
    }

    @POST
    @Path("/{projectId}/explain/search")
    @Consumes(MediaType.APPLICATION_JSON)
    public SearchResponse explainSearch(@PathParam("projectId") UUID projectId, SearchRequest request) throws IOException {
        return search(projectId, request, true);
    }

    @POST
    @Path("/{projectId}/context")
    @Consumes(MediaType.APPLICATION_JSON)
    public ContextResponse context(@PathParam("projectId") UUID projectId, ContextRequestDto request) {
        return context(projectId, request, null);
    }

    @POST
    @Path("/{projectId}/explain/context")
    @Consumes(MediaType.APPLICATION_JSON)
    public ContextResponse explainContext(@PathParam("projectId") UUID projectId, ContextRequestDto request) {
        return context(projectId, request, true);
    }

    private SearchResponse search(UUID projectId, SearchRequest request, Boolean forceExplain) throws IOException {
        requireRequest(request, "Le corps de la requête de recherche est obligatoire");
        String query = requireNonBlank(request.query(), "query est obligatoire");
        int limit = positiveOrDefault(request.limit(), DEFAULT_SEARCH_LIMIT, "limit");
        boolean explain = forceExplain != null ? forceExplain : Boolean.TRUE.equals(request.explain());
        return ApiMapper.search(service.search(projectId, query, limit, explain));
    }

    private ContextResponse context(UUID projectId, ContextRequestDto request, Boolean forceExplain) {
        requireRequest(request, "Le corps de la requête de contexte est obligatoire");
        String query = requireNonBlank(request.query(), "query est obligatoire");
        int tokenBudget = positiveOrDefault(request.tokenBudget(), DEFAULT_TOKEN_BUDGET, "tokenBudget");
        boolean explain = forceExplain != null ? forceExplain : Boolean.TRUE.equals(request.explain());
        Set<String> requestedSources = request.requestedSources() == null ? Set.of() : request.requestedSources();
        Map<String, String> constraints = request.constraints() == null ? Map.of() : request.constraints();
        return ApiMapper.context(service.context(
                projectId,
                query,
                tokenBudget,
                requestedSources,
                constraints,
                explain));
    }

    private static <T> T requireRequest(T request, String message) {
        if (request == null) {
            throw new IllegalArgumentException(message);
        }
        return request;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static int positiveOrDefault(Integer value, int defaultValue, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved <= 0) {
            throw new IllegalArgumentException(field + " doit être strictement positif");
        }
        return resolved;
    }
}
