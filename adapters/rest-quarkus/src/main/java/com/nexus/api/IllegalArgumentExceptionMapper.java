package com.nexus.api;

import com.nexus.api.ApiModels.ErrorResponse;
import com.nexus.search.QueryPolicy;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {

    private static final String UNSUPPORTED_CONSTRAINTS =
            "constraints are not supported yet; omit the field or provide an empty object";
    private static final String OVERSIZED_QUERY_PREFIX =
            "La requête dépasse la limite de " + QueryPolicy.MAX_QUERY_UTF8_BYTES
                    + " octets UTF-8 (taille mesurée ou minimale ";
    private static final String OVERSIZED_QUERY_SUFFIX = " octets)";

    @Override
    public Response toResponse(IllegalArgumentException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse("bad_request", publicMessage(exception)))
                .build();
    }

    private static String publicMessage(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (UNSUPPORTED_CONSTRAINTS.equals(message)) {
            return UNSUPPORTED_CONSTRAINTS;
        }
        if (isOversizedQueryMessage(message)) {
            return message;
        }
        return "Requête invalide";
    }

    private static boolean isOversizedQueryMessage(String message) {
        if (message == null
                || !message.startsWith(OVERSIZED_QUERY_PREFIX)
                || !message.endsWith(OVERSIZED_QUERY_SUFFIX)) {
            return false;
        }
        int numberStart = OVERSIZED_QUERY_PREFIX.length();
        int numberEnd = message.length() - OVERSIZED_QUERY_SUFFIX.length();
        if (numberStart >= numberEnd) {
            return false;
        }
        for (int index = numberStart; index < numberEnd; index++) {
            if (!Character.isDigit(message.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
