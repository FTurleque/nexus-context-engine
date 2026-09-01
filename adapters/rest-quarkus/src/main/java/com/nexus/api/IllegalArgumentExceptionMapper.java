package com.nexus.api;

import com.nexus.api.ApiModels.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    private static final String UNSUPPORTED_CONSTRAINTS =
            "constraints are not supported yet; omit the field or provide an empty object";

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("bad_request", publicMessage(exception)))
                .build();
    }

    private static String publicMessage(IllegalArgumentException exception) {
        return UNSUPPORTED_CONSTRAINTS.equals(exception.getMessage())
                ? UNSUPPORTED_CONSTRAINTS
                : "Requête invalide";
    }
}
