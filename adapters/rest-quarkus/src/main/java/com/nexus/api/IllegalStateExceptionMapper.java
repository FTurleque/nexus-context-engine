package com.nexus.api;

import com.nexus.api.ApiModels.ErrorResponse;
import com.nexus.project.IndexStatus;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps expected project lifecycle conflicts without exposing internal state diagnostics.
 *
 * <p>Only the two lifecycle messages emitted by the core are promoted to HTTP 409.
 * Every other {@link IllegalStateException} remains an internal server failure with a
 * stable public message.</p>
 */
@Provider
public class IllegalStateExceptionMapper implements ExceptionMapper<IllegalStateException> {

    private static final String NOT_READY_PREFIX = "Le projet ";
    private static final String NOT_READY_MARKER = " n'est pas READY (état ";
    private static final String INDEX_BUSY_PREFIX =
            "Une mutation d'index est déjà en cours pour le projet ";

    @Override
    public Response toResponse(IllegalStateException exception) {
        String message = exception.getMessage();
        if (isProjectNotReady(message)) {
            return conflict(
                    "project_not_ready",
                    "Le projet n'est pas prêt pour cette opération");
        }
        if (isProjectIndexBusy(message)) {
            return retryableConflict(
                    "project_index_busy",
                    "Une mutation d'index est déjà en cours pour ce projet");
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(
                        "internal_state_error",
                        "Une erreur interne d'état s'est produite"))
                .build();
    }

    private static boolean isProjectNotReady(String message) {
        if (message == null || !message.startsWith(NOT_READY_PREFIX) || !message.endsWith(")")) {
            return false;
        }
        int markerIndex = message.lastIndexOf(NOT_READY_MARKER);
        if (markerIndex <= NOT_READY_PREFIX.length()) {
            return false;
        }
        int stateStart = markerIndex + NOT_READY_MARKER.length();
        if (stateStart >= message.length() - 1) {
            return false;
        }
        String stateName = message.substring(stateStart, message.length() - 1);
        for (IndexStatus status : IndexStatus.values()) {
            if (status.name().equals(stateName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProjectIndexBusy(String message) {
        if (message == null || !message.startsWith(INDEX_BUSY_PREFIX)) {
            return false;
        }
        return isUuidText(message.substring(INDEX_BUSY_PREFIX.length()));
    }

    private static boolean isUuidText(String value) {
        if (value.length() != 36) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (Character.digit(character, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static Response conflict(String code, String message) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(code, message))
                .build();
    }

    private static Response retryableConflict(String code, String message) {
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", "1")
                .entity(new ErrorResponse(code, message))
                .build();
    }
}
