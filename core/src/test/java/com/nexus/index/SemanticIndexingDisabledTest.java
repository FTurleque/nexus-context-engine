package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticIndexingDisabledTest {

    @Test
    void disabledImplementationIsASharedNoOp() throws IOException {
        SemanticIndexing disabled = SemanticIndexing.disabled();

        assertSame(disabled, SemanticIndexing.disabled());
        assertFalse(disabled.enabled());
        assertTrue(disabled.isCompatible(UUID.randomUUID(), "fingerprint"));

        try (SemanticIndexing.Session session = disabled.openSession(UUID.randomUUID(), "fingerprint", true)) {
            session.applyChanges(List.of(), Set.of("a.txt"));
            session.commit();
        }
    }
}
