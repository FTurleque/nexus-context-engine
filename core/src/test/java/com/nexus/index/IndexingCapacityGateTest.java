package com.nexus.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexingCapacityGateTest {

    @Test
    void rejectsImmediatelyWhenCapacityIsExhaustedAndReleasesPermit() {
        IndexingCapacityGate gate = new IndexingCapacityGate(1);

        try (IndexingCapacityGate.Permit ignored = gate.acquire()) {
            IndexingCapacityExceededException exception = assertThrows(
                    IndexingCapacityExceededException.class,
                    gate::acquire);
            assertEquals(1, exception.limit());
        }

        assertDoesNotThrow(() -> {
            try (IndexingCapacityGate.Permit ignored = gate.acquire()) {
                // Permit is available again after close.
            }
        });
    }

    @Test
    void rejectsInvalidConfiguredLimits() {
        assertThrows(IllegalArgumentException.class, () -> new IndexingCapacityGate(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexingCapacityGate(IndexingCapacityGate.MAX_CONFIGURED_CONCURRENT_INDEXING + 1));
    }
}
