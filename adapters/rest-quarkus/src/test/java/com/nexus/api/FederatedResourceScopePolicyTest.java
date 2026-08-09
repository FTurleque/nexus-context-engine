package com.nexus.api;

import com.nexus.api.ApiModels.FederatedContextRequest;
import com.nexus.api.ApiModels.FederatedSearchRequest;
import com.nexus.project.FederatedScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FederatedResourceScopePolicyTest {

    @Test
    void restRejectsOneHundredAndOneProjectsForSearchAndContextBeforeDelegation() {
        FederatedResource resource = new FederatedResource();
        List<UUID> ids = uniqueIds(101);

        IllegalArgumentException search = assertThrows(
                IllegalArgumentException.class,
                () -> resource.search(new FederatedSearchRequest(ids, "query", 10, false)));
        IllegalArgumentException context = assertThrows(
                IllegalArgumentException.class,
                () -> resource.context(new FederatedContextRequest(
                        ids, "task", 1_000, Set.of(), Map.of(), false)));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, search.getMessage());
        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, context.getMessage());
    }

    private static List<UUID> uniqueIds(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(new UUID(0L, index + 1L));
        }
        return List.copyOf(ids);
    }
}
