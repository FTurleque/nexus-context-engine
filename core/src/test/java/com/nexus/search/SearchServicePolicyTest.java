package com.nexus.search;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchServicePolicyTest {

    @Test
    void refusesEveryNonReadyStateBeforeInvokingAnyStrategy() {
        for (IndexStatus state : List.of(IndexStatus.NOT_INDEXED, IndexStatus.INDEXING, IndexStatus.FAILED)) {
            ProjectDescriptor ready = project();
            ProjectDescriptor unavailable = new ProjectDescriptor(UUID.randomUUID(), ready.name(), ready.rootPath(),
                    ready.sourceType(), ready.languages(), ready.technologies(), null, state);
            assertThrows(IllegalStateException.class,
                    () -> service().search(unavailable, "needle", 10, false));
            assertThrows(IllegalStateException.class,
                    () -> new FederatedSearchService(service()).search(
                            List.of(ready, unavailable), "needle", 10, false));
        }
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesQueryAtServiceBoundary() throws Exception {
        SearchService service = service();
        ProjectDescriptor project = project();

        assertEquals(List.of(), service.search(project, "  needle  ", 10, false));
    }

    @Test
    void rejectsQueriesBeyondPublicUtf8LimitAtServiceBoundary() {
        SearchService service = service();
        ProjectDescriptor project = project();
        String oversized = "é".repeat((QueryPolicy.MAX_QUERY_UTF8_BYTES / 2) + 1);

        assertThrows(IllegalArgumentException.class, () -> service.search(project, oversized, 10, false));
    }

    private SearchService service() {
        return new SearchService(
                List.of(),
                List.of(),
                (request, candidates) -> {
                    assertEquals("needle", request.query());
                    return List.of();
                });
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "policy",
                temporaryDirectory.toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.READY);
    }
}
