package com.nexus.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NexusMcpErrorSanitizationTest {

    @Test
    void preservesActionableValidationMessages() {
        assertEquals(
                "limit doit être inférieur ou égal à 500",
                NexusMcpTools.safeMessage(new IllegalArgumentException(
                        "limit doit être inférieur ou égal à 500")));
    }

    @Test
    void hidesInternalIoDetailsFromClient() {
        String message = NexusMcpTools.safeMessage(new IOException(
                "sqlite failure at /home/user/.nexus/index.db token=secret"));

        assertEquals("Erreur interne NEXUS", message);
        assertFalse(message.contains("/home/user"));
        assertFalse(message.contains("secret"));
    }

    @Test
    void hidesInternalStateDetailsFromClient() {
        String message = NexusMcpTools.safeMessage(new IllegalStateException(
                "project demo is not READY because internal provider x failed"));

        assertEquals("Opération NEXUS indisponible dans l'état courant", message);
        assertFalse(message.contains("provider"));
    }
}
