package com.nexus.search;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedSearchServicePolicyTest {

    private final SearchService searchService = new SearchService(
            List.of(),
            List.of(),
            (request, candidates) -> List.of());
    private final ProjectDescriptor project = new ProjectDescriptor(
            UUID.randomUUID(),
            "policy-test",
            Path.of(".").toAbsolutePath().normalize(),
            ProjectSourceType.LOCAL,
            Set.of("java"),
            Set.of(),
            null,
            IndexStatus.READY);

    @Test
    void rejectsPublicLimitAbovePolicyBeforeAnyProjectSearch() {
        FederatedSearchService federated = new FederatedSearchService(searchService);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> federated.search(
                List.of(project),
                "query",
                ResultLimitPolicy.MAX_RESULT_LIMIT + 1,
                false));

        assertTrue(failure.getMessage().contains("limit must not exceed " + ResultLimitPolicy.MAX_RESULT_LIMIT),
                failure.getMessage());
    }

    @Test
    void rejectsOversizedQueryAtFederatedServiceBoundary() {
        FederatedSearchService federated = new FederatedSearchService(searchService);
        String oversized = "x".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES + 1);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> federated.search(
                List.of(project),
                oversized,
                10,
                false));

        assertTrue(failure.getMessage().contains("limite de " + QueryPolicy.MAX_QUERY_UTF8_BYTES),
                failure.getMessage());
    }
}
