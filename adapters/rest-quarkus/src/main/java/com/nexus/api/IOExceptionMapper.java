package com.nexus.api;

import com.nexus.api.ApiModels.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

@Provider
public class IOExceptionMapper implements ExceptionMapper<IOException> {

    @Override
    public Response toResponse(IOException exception) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("io_error", "Une erreur interne d'entrée/sortie s'est produite"))
                .build();
    }
}
