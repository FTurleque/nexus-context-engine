package com.nexus.project;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FederatedScopePolicyTest {

    @Test
    void acceptsOneAndExactlyOneHundredUniqueProjects() {
        assertEquals(1, FederatedScopePolicy.normalizeProjectIds(uniqueIds(1)).size());
        assertEquals(100, FederatedScopePolicy.normalizeProjectIds(uniqueIds(100)).size());
    }

    @Test
    void rejectsOneHundredAndOneUniqueProjectsWithStableContract() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> FederatedScopePolicy.normalizeProjectIds(uniqueIds(101)));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, exception.getMessage());
    }

    @Test
    void deduplicatesBeforeApplyingCardinalityLimit() {
        List<UUID> unique = uniqueIds(100);
        List<UUID> duplicateHeavy = new ArrayList<>();
        for (int repetition = 0; repetition < 20; repetition++) {
            duplicateHeavy.addAll(unique);
        }

        assertEquals(100, FederatedScopePolicy.normalizeProjectIds(duplicateHeavy).size());
    }

    private static List<UUID> uniqueIds(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(new UUID(0L, index + 1L));
        }
        return List.copyOf(ids);
    }
}
