package com.nexus.api;

import com.nexus.api.ApiModels.ErrorResponse;
import com.nexus.index.IndexingCapacityExceededException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class IndexingCapacityExceededExceptionMapper
        implements ExceptionMapper<IndexingCapacityExceededException> {

    @Override
    public Response toResponse(IndexingCapacityExceededException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(
                        "indexing_busy",
                        "Capacité d'indexation temporairement saturée"))
                .build();
    }
}
