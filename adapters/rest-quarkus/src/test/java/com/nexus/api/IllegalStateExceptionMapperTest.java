package com.nexus.api;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IllegalStateExceptionMapperTest {

    @Test
    void mapsNotReadyProjectToConflictWithoutExposingProjectName() {
        String internalName = "private-project";

        try (Response response = new IllegalStateExceptionMapper().toResponse(
                new IllegalStateException(
                        "Le projet " + internalName + " n'est pas READY (état NOT_INDEXED)"))) {
            ApiModels.ErrorResponse entity = (ApiModels.ErrorResponse) response.getEntity();
            assertEquals(409, response.getStatus());
            assertEquals("project_not_ready", entity.error());
            assertFalse(entity.message().contains(internalName));
        }
    }

    @Test
    void mapsConcurrentProjectIndexMutationToRetryableConflict() {
        try (Response response = new IllegalStateExceptionMapper().toResponse(
                new IllegalStateException(
                        "Une mutation d'index est déjà en cours pour le projet "
                                + "01234567-89ab-cdef-0123-456789abcdef"))) {
            ApiModels.ErrorResponse entity = (ApiModels.ErrorResponse) response.getEntity();
            assertEquals(409, response.getStatus());
            assertEquals("1", response.getHeaderString("Retry-After"));
            assertEquals("project_index_busy", entity.error());
        }
    }

    @Test
    void unknownIllegalStateRemainsSanitizedInternalFailure() {
        String internal = "C:\\private\\state\\secret.txt";

        try (Response response = new IllegalStateExceptionMapper().toResponse(
                new IllegalStateException(internal))) {
            ApiModels.ErrorResponse entity = (ApiModels.ErrorResponse) response.getEntity();
            assertEquals(500, response.getStatus());
            assertEquals("internal_state_error", entity.error());
            assertFalse(entity.message().contains(internal));
        }
    }
}
