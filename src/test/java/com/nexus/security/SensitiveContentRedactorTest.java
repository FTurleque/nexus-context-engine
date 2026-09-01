package com.nexus.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveContentRedactorTest {

    @Test
    void redactsStructuredSecretsAndKeepsSurroundingCode() {
        String source = """
                String password = "correctHorseBatteryStaple";
                String token = "ghp_abcdefghijklmnopqrstuvwxyz123456";
                String endpoint = "https://alice:supersecret@example.test/api";
                int retries = 3;
                """;

        String redacted = SensitiveContentRedactor.redact(source);

        assertFalse(redacted.contains("correctHorseBatteryStaple"));
        assertFalse(redacted.contains("ghp_abcdefghijklmnopqrstuvwxyz123456"));
        assertFalse(redacted.contains("supersecret"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("int retries = 3;"));
    }

    @Test
    void redactsPrivateKeyBlocks() {
        String source = """
                before
                -----BEGIN PRIVATE KEY-----
                abcdefghijklmnopqrstuvwxyz
                -----END PRIVATE KEY-----
                after
                """;

        String redacted = SensitiveContentRedactor.redact(source);

        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(redacted.contains("before"));
        assertTrue(redacted.contains("after"));
    }
}
