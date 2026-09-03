package com.nexus.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FederatedSearchServicePolicyTest {

    private final SearchService searchService = new SearchService(
            List.of(),
            List.of(),
            (request, candidates) -> List.of());

    @Test
    void rejectsPublicLimitAbovePolicyBeforeAnyProjectSearch() {
        FederatedSearchService federated = new FederatedSearchService(searchService);

        assertThrows(IllegalArgumentException.class, () -> federated.search(
                List.of(),
                "query",
                ResultLimitPolicy.MAX_RESULT_LIMIT + 1,
                false));
    }

    @Test
    void rejectsOversizedQueryAtFederatedServiceBoundary() {
        FederatedSearchService federated = new FederatedSearchService(searchService);
        String oversized = "x".repeat(QueryPolicy.MAX_QUERY_UTF8_BYTES + 1);

        assertThrows(IllegalArgumentException.class, () -> federated.search(
                List.of(),
                oversized,
                10,
                false));
    }
}
