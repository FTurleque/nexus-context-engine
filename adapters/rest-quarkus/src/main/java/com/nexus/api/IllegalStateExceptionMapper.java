package com.nexus.api;

import com.nexus.api.ApiModels.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Pattern;

/**
 * Maps expected project lifecycle conflicts without exposing internal state diagnostics.
 *
 * <p>Only the two lifecycle messages emitted by the core are promoted to HTTP 409.
 * Every other {@link IllegalStateException} remains an internal server failure with a
 * stable public message.</p>
 */
@Provider
public class IllegalStateExceptionMapper implements ExceptionMapper<IllegalStateException> {

    private static final Pattern PROJECT_NOT_READY = Pattern.compile(
            "^Le projet .+ n'est pas READY \\(état [A-Z_]+\\)$");
    private static final Pattern PROJECT_INDEX_BUSY = Pattern.compile(
            "^Une mutation d'index est déjà en cours pour le projet "
                    + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public Response toResponse(IllegalStateException exception) {
        String message = exception.getMessage();
        if (message != null && PROJECT_NOT_READY.matcher(message).matches()) {
            return conflict(
                    "project_not_ready",
                    "Le projet n'est pas prêt pour cette opération",
                    false);
        }
        if (message != null && PROJECT_INDEX_BUSY.matcher(message).matches()) {
            return conflict(
                    "project_index_busy",
                    "Une mutation d'index est déjà en cours pour ce projet",
                    true);
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(
                        "internal_state_error",
                        "Une erreur interne d'état s'est produite"))
                .build();
    }

    private static Response conflict(String code, String message, boolean retryable) {
        Response.ResponseBuilder response = Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(code, message));
        if (retryable) {
            response.header("Retry-After", "1");
        }
        return response.build();
    }
}
