package com.nexus.api;

import com.nexus.index.IndexingCapacityExceededException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExceptionMapperDisclosureTest {

    @Test
    void ioMapperDoesNotExposeInternalExceptionMessage() {
        String internal = "C:\\private\\workspace\\secret.txt";

        try (Response response = new IOExceptionMapper().toResponse(new IOException(internal))) {
            ApiModels.ErrorResponse entity = (ApiModels.ErrorResponse) response.getEntity();
            assertEquals(500, response.getStatus());
            assertEquals("io_error", entity.error());
            assertFalse(entity.message().contains(internal));
        }
    }

    @Test
    void invalidArgumentMapperDoesNotExposeInternalValidationMessage() {
        String internal = "/srv/internal/project/path";

        try (Response response = new IllegalArgumentExceptionMapper()
                .toResponse(new IllegalArgumentException(internal))) {
            ApiModels.ErrorResponse entity = (ApiModels.ErrorResponse) response.getEntity();
            assertEquals(400, response.getStatus());
            assertEquals("bad_request", entity.error());
            assertFalse(entity.message().contains(internal));
        }
    }

    @Test
    void indexingCapacityMapsToRetryableServiceUnavailable() {
        try (Response response = new IndexingCapacityExceededExceptionMapper()
                .toResponse(new IndexingCapacityExceededException(2))) {
            ApiModels.ErrorResponse entity = (ApiModels.ErrorResponse) response.getEntity();
            assertEquals(503, response.getStatus());
            assertEquals("1", response.getHeaderString("Retry-After"));
            assertEquals("indexing_busy", entity.error());
        }
    }
}
