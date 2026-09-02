package com.nexus.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void redactsPunctuatedQuotedSecrets() {
        String source = "password=\"P@ssw0rd!2026#prod\";";

        String redacted = SensitiveContentRedactor.redact(source);

        assertFalse(redacted.contains("P@ssw0rd!2026#prod"));
        assertEquals("password=\"[REDACTED]\";", redacted);
    }

    @Test
    void redactsQuotedSecretsContainingSpaces() {
        String source = "password = \"correct horse battery staple\";";

        String redacted = SensitiveContentRedactor.redact(source);

        assertFalse(redacted.contains("correct horse battery staple"));
        assertEquals("password = \"[REDACTED]\";", redacted);
    }

    @Test
    void redactsCompositeSecretKeysAcrossCommonConfigurationStyles() {
        String source = """
                DB_PASSWORD="SuperSecret123!"
                AWS_SECRET_ACCESS_KEY='wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY'
                MY_CLIENT_SECRET=client-secret-value
                database.password: another-long-secret
                """;

        String redacted = SensitiveContentRedactor.redact(source);

        assertFalse(redacted.contains("SuperSecret123!"));
        assertFalse(redacted.contains("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));
        assertFalse(redacted.contains("client-secret-value"));
        assertFalse(redacted.contains("another-long-secret"));
        assertTrue(redacted.contains("DB_PASSWORD=\"[REDACTED]\""));
        assertTrue(redacted.contains("AWS_SECRET_ACCESS_KEY='[REDACTED]'"));
        assertTrue(redacted.contains("MY_CLIENT_SECRET=[REDACTED]"));
        assertTrue(redacted.contains("database.password: [REDACTED]"));
    }

    @Test
    void doesNotRedactIdentifiersThatMerelyContainSecretAsSubstring() {
        String source = "notasecretvalue = \"ordinary configuration value\";";

        assertEquals(source, SensitiveContentRedactor.redact(source));
    }

    @Test
    void redactsPrivateKeyBlocksWithoutChangingSourceLineCount() {
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
        assertEquals(source.lines().count(), redacted.lines().count());
    }

    @Test
    void redactsTruncatedPrivateKeyBlockThroughEndOfContent() {
        String source = """
                before
                -----BEGIN PRIVATE KEY-----
                abcdefghijklmnopqrstuvwxyz
                still-secret
                """;

        String redacted = SensitiveContentRedactor.redact(source);

        assertTrue(redacted.contains("before"));
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz"));
        assertFalse(redacted.contains("still-secret"));
        assertEquals(source.lines().count(), redacted.lines().count());
    }
}
