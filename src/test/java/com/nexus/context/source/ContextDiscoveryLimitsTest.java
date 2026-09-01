package com.nexus.context.source;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextDiscoveryLimitsTest {

    @Test
    void acceptsExactBoundariesAndRejectsNPlusOne() {
        ContextDiscoveryBudget budget = new ContextDiscoveryLimits(2, 2, 10, 10_000).newBudget();

        assertDoesNotThrow(() -> budget.visit(Path.of("a")));
        assertDoesNotThrow(() -> budget.visit(Path.of("b")));
        assertThrows(ContextDiscoveryLimitExceededException.class, () -> budget.visit(Path.of("c")));

        ContextDiscoveryBudget candidates = new ContextDiscoveryLimits(10, 2, 10, 10_000).newBudget();
        assertDoesNotThrow(() -> candidates.candidate(Path.of("a")));
        assertDoesNotThrow(() -> candidates.candidate(Path.of("b")));
        assertThrows(ContextDiscoveryLimitExceededException.class, () -> candidates.candidate(Path.of("c")));

        ContextDiscoveryBudget bytes = new ContextDiscoveryLimits(10, 10, 10, 10_000).newBudget();
        assertDoesNotThrow(() -> bytes.bytes(Path.of("a"), 4));
        assertDoesNotThrow(() -> bytes.bytes(Path.of("b"), 6));
        assertThrows(ContextDiscoveryLimitExceededException.class, () -> bytes.bytes(Path.of("c"), 1));
    }

    @Test
    void readsEnvironmentOverridesAndFailsClosedOnInvalidConfiguration() {
        ContextDiscoveryLimits limits = ContextDiscoveryLimits.from(Map.of(
                ContextDiscoveryLimits.ENV_MAX_VISITED_ENTRIES, "123",
                ContextDiscoveryLimits.ENV_MAX_CANDIDATES, "45",
                ContextDiscoveryLimits.ENV_MAX_BYTES, "6789",
                ContextDiscoveryLimits.ENV_MAX_MILLIS, "9876"));

        assertEquals(123, limits.maxVisitedEntries());
        assertEquals(45, limits.maxCandidateResources());
        assertEquals(6789, limits.maxCumulativeBytes());
        assertEquals(9876, limits.maxElapsedMillis());

        Map<String, String> zeroVisitedEntries = Map.of(
                ContextDiscoveryLimits.ENV_MAX_VISITED_ENTRIES, "0");
        assertThrows(IllegalArgumentException.class, () -> ContextDiscoveryLimits.from(zeroVisitedEntries));

        Map<String, String> invalidByteLimit = Map.of(
                ContextDiscoveryLimits.ENV_MAX_BYTES, "not-a-number");
        assertThrows(IllegalArgumentException.class, () -> ContextDiscoveryLimits.from(invalidByteLimit));
    }
}
