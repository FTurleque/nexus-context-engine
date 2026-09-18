package com.nexus.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveContentRedactorTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"123456789", "true", "false", "null",
            "synthetic-unquoted-secret-123456", "correct horse battery staple", "Unicode-é漢字🔐",
            "punctuation!@#:value", "x", ""})
    void redactsScalarsWithEveryKeySyntax(String value) {
        for (String key : java.util.List.of("password", "database.password", "\"password\"", "'api_key'")) {
            for (String separator : java.util.List.of(": ", "=")) {
                String result = SensitiveContentRedactor.redact(key + separator + value);
                assertEquals(key + separator + "[REDACTED]", result);
                assertEquals(result, SensitiveContentRedactor.redact(result));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\n", "\r\n", "\r"})
    void preservesExactSeparatorsAndDocumentBoundaries(String newline) {
        String input = "{\"password\": 123456789,\"api_key\":true}" + newline
                + "'client_secret': synthetic value # public comment" + newline
                + "ordinary: keep" + newline + "password: abc#def:ghi]";
        String expected = "{\"password\": [REDACTED],\"api_key\":[REDACTED]}" + newline
                + "'client_secret': [REDACTED] # public comment" + newline
                + "ordinary: keep" + newline + "password: [REDACTED]]";
        assertEquals(expected, SensitiveContentRedactor.redact(input));
        assertEquals(expected, SensitiveContentRedactor.redact(expected));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"\"", "''", "\"x\"", "'x'",
            "\"synthetic: #, 漢字\"", "'synthetic''apostrophe'"})
    void redactsQuotedValuesIncludingEmptyAndShortValues(String value) {
        String quote = value.substring(0, 1);
        assertEquals("\"password\": " + quote + "[REDACTED]" + quote,
                SensitiveContentRedactor.redact("\"password\": " + value));
    }

    @Test
    void markerPrefixDoesNotAllowASecretSuffixToEscape() {
        assertEquals("password=[REDACTED]", SensitiveContentRedactor.redact("password=[REDACTED]synthetic-tail"));
        String composite = "prefix.".repeat(1000) + "password=synthetic-value";
        assertEquals("prefix.".repeat(1000) + "password=[REDACTED]", SensitiveContentRedactor.redact(composite));
    }

    @Test
    void malformedKeysAndOversizedValuesHaveBoundedLookahead() {
        String malformed = "a.".repeat(100_000) + "ordinary = value";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertEquals(malformed, SensitiveContentRedactor.redact(malformed));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> SensitiveContentRedactor.redact("password=" + "x".repeat(1_000_000)));
        });
    }

    @Test
    void rejectsOversizedValuesWithoutPublishingAnySuffix() {
        for (String quote : java.util.List.of("", "\"", "'")) {
            String prefix = "\"password\": " + quote;
            assertEquals(prefix + "[REDACTED]" + quote,
                    SensitiveContentRedactor.redact(prefix + "x".repeat(4096) + quote));
            var failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> SensitiveContentRedactor.redact(prefix + "x".repeat(4097) + quote));
            assertFalse(failure.getMessage().contains("xxxxxxxx"));
        }
    }

    @Test
    void malformedQuotedValuesNeverConsumeAnotherLine() {
        String input = "\"password\": \"synthetic\\\"unfinished\r\nordinary: visible\npassword=short";
        String expected = "\"password\": \"[REDACTED]\r\nordinary: visible\npassword=[REDACTED]";
        assertEquals(expected, SensitiveContentRedactor.redact(input));
        assertEquals(expected, SensitiveContentRedactor.redact(expected));
    }

    @Test
    void redactsQuotedJsonAndJavascriptKeysIncludingEscapedValues() {
        String source = "{\"password\":\"synthetic\\\"secret123\",'api_key':'synthetic\\\\secret456',\"ordinary\":\"keep value\"}";
        String redacted = SensitiveContentRedactor.redact(source);
        assertEquals("{\"password\":\"[REDACTED]\",'api_key':'[REDACTED]',\"ordinary\":\"keep value\"}", redacted);
        assertEquals(redacted, SensitiveContentRedactor.redact(redacted));
    }

    @Test
    void quotedSecretScanIsBoundedAndPreservesLinesAndUnrelatedKeys() {
        String source = "```json\r\n{\"client_secret\":\"" + "x".repeat(4096)
                + "\",\"notasecretvalue\":\"ordinary value\"}\r\n```";
        String redacted = SensitiveContentRedactor.redact(source);
        assertFalse(redacted.contains("x".repeat(8)));
        assertTrue(redacted.contains("ordinary value"));
        assertEquals(source.lines().count(), redacted.lines().count());
    }

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
