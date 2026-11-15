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
        // Garde-fou contre un lookahead quadratique (bloquant en pratique sur 200 000 caractères),
        // pas une mesure de performance : marge supérieure à x5 sur la durée nominale (~0,3 s à froid).
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            assertEquals(malformed, SensitiveContentRedactor.redact(malformed));
            String oversized = "password=" + "x".repeat(1_000_000);
            org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                    () -> SensitiveContentRedactor.redact(oversized));
        });
    }

    @Test
    void forgedPrivateKeyMarkerRepetitionsHaveBoundedCost() {
        String dashes = "-".repeat(5);
        // Garde-fou contre un balayage quadratique des marqueurs de clé privée, pas une mesure de
        // performance : six contenus d'environ 1 Mo, seuil de 10 s comme le garde-fou précédent.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            for (String repeated : java.util.List.of(dashes + "BEGIN \n", dashes + "BEGIN CERTIFICATE" + dashes + "\n",
                    dashes + "BEGIN ", dashes + "begin\t", dashes + "BEGIN" + (char) 0x00A0 + "X")) {
                String markers = repeated.repeat((1 << 20) / repeated.length() + 1);
                assertEquals(markers, SensitiveContentRedactor.redact(markers));
            }
            String partialEnd = dashes + "END RSA PRIVATE KE\n";
            String unclosed = dashes + "BEGIN RSA PRIVATE KEY" + dashes + "\n"
                    + partialEnd.repeat((1 << 20) / partialEnd.length() + 1);
            String rendered = SensitiveContentRedactor.redact(unclosed);
            assertTrue(rendered.startsWith("[REDACTED]\n"), rendered.substring(0, 40));
            String spacedType = dashes + "BEGIN A" + " ".repeat(500_000) + "PRIVATE KEY" + dashes + "\n"
                    + (dashes + "END A X\n").repeat(40_000) + "  AAA" + dashes + "END A PRIVATE KEY" + dashes + "x\n";
            assertTrue(SensitiveContentRedactor.redact(spacedType).startsWith("[REDACTED]\n"));
        });
    }

    @Test
    void endThenBeginRepetitionsHaveBoundedCost() {
        String dashes = "-".repeat(5);
        String block = dashes + "BEGIN PRIVATE KEY" + dashes;
        String end = dashes + "END PRIVATE KEY" + dashes;
        // Garde-fou contre un balayage quadratique de la ligne d'un END, pas une mesure de performance :
        // contenus d'environ 1 Mo, seuil de 10 s comme les garde-fous voisins.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            String oneLineCycles = (block + "x" + end).repeat((1 << 20) / (block.length() + 1 + end.length()) + 1);
            assertFalse(SensitiveContentRedactor.redact(oneLineCycles).contains("PRIVATE KEY"));

            String cyclesPerLine = (block + "\nx" + end + "\n").repeat((1 << 20) / (block.length() + end.length() + 3) + 1);
            assertFalse(SensitiveContentRedactor.redact(cyclesPerLine).contains("PRIVATE KEY"));

            String otherBegins = block + "\nx" + end + (dashes + "BEGIN CERTIFICATE ").repeat((1 << 20) / 23 + 1) + "\nafter";
            String rendered = SensitiveContentRedactor.redact(otherBegins);
            assertEquals("[REDACTED]\n\nafter", rendered);

            String dashRun = block + "\nx" + end + "-".repeat(1 << 20) + "\nafter";
            assertTrue(SensitiveContentRedactor.redact(dashRun).endsWith("\nafter"));

            String dashedAfterLineStartEnd = block + "\n" + end + (dashes + "x ").repeat((1 << 20) / 7 + 1);
            assertTrue(SensitiveContentRedactor.redact(dashedAfterLineStartEnd).startsWith("[REDACTED]\n"));
        });
    }

    @Test
    void rejectsOversizedValuesWithoutPublishingAnySuffix() {
        for (String quote : java.util.List.of("", "\"", "'")) {
            String prefix = "\"password\": " + quote;
            assertEquals(prefix + "[REDACTED]" + quote,
                    SensitiveContentRedactor.redact(prefix + "x".repeat(4096) + quote));
            String aboveBound = prefix + "x".repeat(4097) + quote;
            var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                    () -> SensitiveContentRedactor.redact(aboveBound));
            assertFalse(failure.getMessage().contains("xxxxxxxx"));
        }
    }

    @Test
    void boundedFormsAreRedactedAtTheBoundAndRefusedJustAbove() {
        for (String prefix : java.util.List.of("Authorization: Bearer ", "curl -H 'X: Bearer ",
                "run --password ", "<password>")) {
            String suffix = prefix.equals("<password>") ? "</password>" : "";
            String atBound = SensitiveContentRedactor.redact(prefix + "v".repeat(4096) + suffix);
            assertFalse(atBound.contains("vvvv"), atBound);
            String aboveBound = prefix + "v".repeat(4097) + suffix;
            var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                    () -> SensitiveContentRedactor.redact(aboveBound));
            assertFalse(failure.getMessage().contains("vvvv"));
        }
        String user = "u".repeat(1024);
        assertFalse(SensitiveContentRedactor.redact("https://" + user + "@host/repo").contains("uuuu"));
        String longUser = "https://" + user + "u@host/repo";
        org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(longUser));
    }

    @Test
    void masksUriUserPartAtExactly1024Characters() {
        String user = syntheticAlphanumeric(1024);
        String content = "clone https://" + user + "@host/repo done";
        String redacted = SensitiveContentRedactor.redact(content);
        assertEquals("clone https://[REDACTED]@host/repo done", redacted);
        assertNoFragmentOf(user, redacted);
    }

    @Test
    void refusesUriUserPartOf1025CharactersWithoutPublishingAnyPartOfIt() {
        String user = syntheticAlphanumeric(1025);
        String content = "clone https://" + user + "@host/repo done\npassword: other-secret-value";
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(content));
        assertFalse(failure.getMessage().contains(user.substring(500, 532)), failure.getMessage());
        assertNoFragmentOf(user, failure.getMessage());
        // Aucun contenu partiellement redigé : un champ public refusé devient [REDACTED] en entier.
        assertEquals("[REDACTED]", SensitiveContentRedactor.redactField(content));
    }

    @Test
    void masksGithubTokenWhoseBodyHas255Characters() {
        String token = "gh" + "p_" + syntheticAlphanumeric(255);
        String redacted = SensitiveContentRedactor.redact("x " + token + " y");
        assertEquals("x [REDACTED] y", redacted);
        assertNoFragmentOf(token.substring(4), redacted);
    }

    @Test
    void refusesGithubTokenWhoseBodyHas256CharactersWithoutPublishingAnyPartOfIt() {
        String token = "gh" + "p_" + syntheticAlphanumeric(256);
        String content = "x " + token + " y";
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(content));
        assertFalse(failure.getMessage().contains(token.substring(100, 132)), failure.getMessage());
        assertNoFragmentOf(token, failure.getMessage());
        assertEquals("[REDACTED]", SensitiveContentRedactor.redactField(content));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "sk-{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "proj-", "ant-"})
    void masksSkTokenWhoseBodyHas255CharactersAfterItsPrefix(String marker) {
        String body = syntheticAlphanumeric(255);
        String token = "sk-" + marker + body;
        String redacted = SensitiveContentRedactor.redact("x " + token + " y");
        assertEquals("x [REDACTED] y", redacted);
        assertNoFragmentOf(body, redacted);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "sk-{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "proj-", "ant-"})
    void refusesSkTokenWhoseBodyHas256CharactersWithoutPublishingAnyPartOfIt(String marker) {
        assertSkTokenRefused(marker, 256);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "corps de {0} caractères")
    @org.junit.jupiter.params.provider.ValueSource(ints = {256, 257, 258, 259, 260})
    void refusesSkTokensWhoseBodyHas256To260Characters(int bodyLength) {
        assertSkTokenRefused("", bodyLength);
    }

    private static void assertSkTokenRefused(String marker, int bodyLength) {
        String token = "sk-" + marker + syntheticAlphanumeric(bodyLength);
        String content = "x " + token + " y";
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(content));
        assertNoFragmentOf(token, failure.getMessage());
        assertEquals("[REDACTED]", SensitiveContentRedactor.redactField(content));
    }

    /** Bloc de clé privée synthétique, marqueurs assemblés à l'exécution, sans corps de clé réel. */
    private static final String PRIVATE_KEY_BLOCK = rsaPrivateKeyBlock();

    private static String rsaPrivateKeyBlock() {
        String dashes = "-".repeat(5);
        String type = "RSA PRIVATE KEY";
        return String.join("\n", dashes + "BEGIN " + type + dashes, "MII", dashes + "END " + type + dashes);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sk-", "sk-ant-", "glpat-", "xoxb-"})
    void masksTokenGluedToAPrivateKeyBlockWhoseBodyHas255Characters(String prefix) {
        String body = syntheticAlphanumeric(255);
        String redacted = SensitiveContentRedactor.redact(PRIVATE_KEY_BLOCK + prefix + body + " y");
        assertEquals("[REDACTED]\n\n[REDACTED] y", redacted);
        assertNoFragmentOf(body, redacted);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sk-", "sk-ant-", "glpat-", "xoxb-"})
    void refusesTokenGluedToAPrivateKeyBlockWhoseBodyHas256Characters(String prefix) {
        assertTokenRefused(PRIVATE_KEY_BLOCK + prefix, syntheticAlphanumeric(256));
        assertTokenRefused(PRIVATE_KEY_BLOCK + prefix, syntheticAlphanumeric(300));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"xoxb-", "gh" + "p_", "sk" + "_live_"})
    void masksShortTokenContainingAnUnderscore(String prefix) {
        String body = syntheticAlphanumeric(25) + "_" + syntheticAlphanumeric(10);
        String redacted = SensitiveContentRedactor.redact("x " + prefix + body + " y");
        assertEquals("x [REDACTED] y", redacted);
        assertNoFragmentOf(body, redacted);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"xoxb-", "gh" + "p_", "sk" + "_live_"})
    void masksTokenWhoseBodyHas255CharactersIncludingAnUnderscore(String prefix) {
        String body = syntheticAlphanumeric(127) + "_" + syntheticAlphanumeric(127);
        String redacted = SensitiveContentRedactor.redact("x " + prefix + body + " y");
        assertEquals("x [REDACTED] y", redacted);
        assertNoFragmentOf(body, redacted);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"xoxb-", "gh" + "p_", "sk" + "_live_"})
    void refusesTokenWhoseBodyHas256CharactersIncludingAnUnderscore(String prefix) {
        assertTokenRefused("x " + prefix, syntheticAlphanumeric(127) + "_" + syntheticAlphanumeric(128));
    }

    private static void assertTokenRefused(String before, String body) {
        String content = before + body + " y";
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(content));
        assertNoFragmentOf(body, failure.getMessage());
        assertEquals("[REDACTED]", SensitiveContentRedactor.redactField(content));
    }

    /** Valeur alphanumérique synthétique, construite par code (suites de l'alphabet décalées par bloc). */
    private static String syntheticAlphanumeric(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt((i + i / alphabet.length()) % alphabet.length()));
        }
        return value.toString();
    }

    /** Aucune fenêtre de 8 caractères de la valeur n'apparaît dans le texte publié. */
    private static void assertNoFragmentOf(String value, String published) {
        for (int start = 0; start + 8 <= value.length(); start++) {
            String fragment = value.substring(start, start + 8);
            assertFalse(published.contains(fragment), () -> "fragment publié : " + fragment);
        }
    }

    @Test
    void historicalTokensFollowedByAHyphenStayRedacted() {
        String github = "gh" + "p_" + "abcdefghijklmnopqrstuvwxyz0123456789";
        String aws = "AK" + "IA" + "ABCDEFGHIJKLMNOP";
        assertEquals("x [REDACTED]-ci y", SensitiveContentRedactor.redact("x " + github + "-ci y"));
        assertEquals("[REDACTED]-prod", SensitiveContentRedactor.redact(aws + "-prod"));
        assertEquals("a-[REDACTED]", SensitiveContentRedactor.redact("a-" + aws));
    }

    @Test
    void commandLineAndXmlVariantsAreRedacted() {
        for (String input : java.util.List.of(
                "mysql --password \"hunter2-quoted-secret\" --user app",
                "cmd --token 'hunter2-quoted-secret'",
                "deploy --db-password hunter2-quoted-secret",
                "gh --access-token hunter2-quoted-secret",
                "<password type=\"plain\">hunter2-quoted-secret</password>",
                "<db.password>hunter2-quoted-secret</db.password>",
                "<password><![CDATA[hunter2-quoted-secret]]></password>",
                "Authorization: Bearer          hunter2quotedsecret0123")) {
            String redacted = SensitiveContentRedactor.redact(input);
            assertFalse(redacted.contains("hunter2"), redacted);
            assertTrue(redacted.contains("[REDACTED]"), redacted);
        }
        assertEquals("--password-file /etc/app.pw", SensitiveContentRedactor.redact("--password-file /etc/app.pw"));
    }

    @Test
    void valuesAboveTheirBoundAreRefusedInsteadOfPublished() {
        for (String input : java.util.List.of(
                "sk-" + "a".repeat(300),
                "gh" + "p_" + "b".repeat(300),
                "xox" + "b-" + "c".repeat(300),
                "https://user:" + "p".repeat(5_000) + "@host/repo",
                "https://" + "u".repeat(2_000) + ":pw@host/repo",
                "mysql --password \"" + "q".repeat(5_000) + "\"",
                "<db.password>" + "r".repeat(5_000) + "</db.password>")) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                    () -> SensitiveContentRedactor.redact(input));
            assertFalse(failure.getMessage().contains("aaaa"));
        }
        assertEquals("[REDACTED]", SensitiveContentRedactor.redactField("password = \"" + "s".repeat(5_000) + "\""));
        org.junit.jupiter.api.Assertions.assertNull(SensitiveContentRedactor.redactField(null));
    }

    @Test
    void newKeyComponentsMatchWholeSegmentsOnly() {
        for (String ordinary : java.util.List.of("tokenizer = build()", "authority: local", "oauth_flow=pkce",
                "max_tokens = 4096", "pwd_history: 3", "secretary: Jane")) {
            if (ordinary.startsWith("pwd_history")) {
                assertEquals("pwd_history: [REDACTED]", SensitiveContentRedactor.redact(ordinary));
            } else {
                assertEquals(ordinary, SensitiveContentRedactor.redact(ordinary));
            }
        }
        assertEquals("page_token=[REDACTED]", SensitiveContentRedactor.redact("page_token=abc123"));
        assertEquals("accessKey: [REDACTED]", SensitiveContentRedactor.redact("accessKey: abc123"));
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
    void redactsJsonSecretsWhilePreservingQuotedKeys() {
        String source = "{\"password\":\"AuditSyntheticPassword98765\",\"client_secret\":\"AuditSyntheticClientSecret98765\"}";

        String redacted = SensitiveContentRedactor.redact(source);

        assertEquals(
                "{\"password\":\"[REDACTED]\",\"client_secret\":\"[REDACTED]\"}",
                redacted);
    }

    @Test
    void doesNotRedactIdentifiersThatMerelyContainSecretAsSubstring() {
        String source = "notasecretvalue = \"ordinary configuration value\";";

        assertEquals(source, SensitiveContentRedactor.redact(source));
    }

    @Test
    void handlesVeryLongCompositeKeysWithoutRecursiveMatching() {
        String prefix = "configuration_".repeat(20_000);
        String source = prefix + "PASSWORD=\"SyntheticSecret12345\";\n"
                + prefix + "ordinary=\"ordinary value\";";
        String expected = prefix + "PASSWORD=\"[REDACTED]\";\n"
                + prefix + "ordinary=\"ordinary value\";";
        assertEquals(expected, SensitiveContentRedactor.redact(source));
    }

    @Test
    void recognizesAllSupportedCompoundSpellingsAndKeepsKeyBoundaries() {
        for (String name : new String[]{"apikey", "api_key", "api-key", "accessToken", "access_token",
                "access-token", "authToken", "auth_token", "auth-token", "clientSecret", "client_secret",
                "client-secret", "secretaccesskey", "secretaccess_key", "secretaccess-key", "secret_access_key"}) {
            String key = "'service." + name + ".production'";
            assertEquals(key + ": '[REDACTED]'", SensitiveContentRedactor.redact(key + ": 'SyntheticSecret12345'"));
        }
        String ordinary = "notapassword=\"ordinary value\"; passwordHint=\"ordinary value\"; πpassword=\"ordinary value\";";
        assertEquals(ordinary, SensitiveContentRedactor.redact(ordinary));
    }

    @Test
    void preservesIncompleteAssignmentsAndMasksPrivateCompositeKeys() {
        assertEquals("_password = '[REDACTED]'", SensitiveContentRedactor.redact("_password = 'SyntheticSecret12345'"));
        // Politique v5 : les valeurs courtes ou vides sont masquées (voir les tests de scalaires ci-dessus).
        for (String source : new String[]{"password", "'password\" = 'ordinary value'"}) {
            assertEquals(source, SensitiveContentRedactor.redact(source));
        }
    }

    @Test
    void redactsEscapedQuotesWithoutLeakingSuffixOrChangingDelimiters() {
        String source = "{\"password\":\"AuditPrefix123\\\"VISIBLE_SECRET_SUFFIX\",\"count\":2}";
        assertEquals("{\"password\":\"[REDACTED]\",\"count\":2}", SensitiveContentRedactor.redact(source));
        assertEquals("{'password': '[REDACTED]'}",
                SensitiveContentRedactor.redact("{'password': 'AuditPrefix123\\'VISIBLE_SECRET_SUFFIX'}"));
    }

    @Test
    void redactsSingleQuotedKeysAndHandlesEvenBackslashesBeforeClosingQuote() {
        assertEquals("{'password': '[REDACTED]', 'safe': 'ordinary'}",
                SensitiveContentRedactor.redact("{'password': 'AuditSynthetic98765', 'safe': 'ordinary'}"));
        assertEquals("{\"password\":\"[REDACTED]\",\"safe\":\"ordinary\"}",
                SensitiveContentRedactor.redact("{\"password\":\"AuditSecret123\\\\\",\"safe\":\"ordinary\"}"));
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

    /** Corps factice des blocs assemblés à l'exécution ; aucun matériau de clé réel. */
    private static final String FAKE_KEY_BODY = "MIIfakebodyNOTAKEY0123";
    private static final String DASHES = "-".repeat(5);

    private static String privateKeyBlock(String type, String newline) {
        return DASHES + "BEGIN " + type + DASHES + newline + FAKE_KEY_BODY + newline + DASHES + "END " + type + DASHES;
    }

    private static String lineSeparatorsOf(String content) {
        var separators = new StringBuilder();
        for (char character : content.toCharArray()) {
            if (character == '\r' || character == '\n') {
                separators.append(character);
            }
        }
        return separators.toString();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> privateKeyBlocksAfterAMarkerThatOpensNoKey() {
        String rsa = privateKeyBlock("RSA PRIVATE KEY", "\n");
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("ligne BEGIN incomplète",
                        DASHES + "BEGIN \n" + rsa),
                org.junit.jupiter.params.provider.Arguments.of("littéral de code",
                        "if (line.startsWith(\"" + DASHES + "BEGIN \")) {}\n" + rsa),
                org.junit.jupiter.params.provider.Arguments.of("marqueur d'un autre type sans tirets de fin",
                        DASHES + "BEGIN CERT\n" + rsa),
                org.junit.jupiter.params.provider.Arguments.of("marqueur complet d'un autre type collé",
                        DASHES + "BEGIN CERTIFICATE" + DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + "\n"
                                + FAKE_KEY_BODY + "\n" + DASHES + "END RSA PRIVATE KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("marqueur d'un autre type collé par six tirets",
                        DASHES + "BEGIN X-" + DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + "\n"
                                + FAKE_KEY_BODY + "\n" + DASHES + "END RSA PRIVATE KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("bloc PGP après une ligne BEGIN incomplète",
                        DASHES + "BEGIN \n" + privateKeyBlock("PGP PRIVATE KEY BLOCK", "\n")));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("privateKeyBlocksAfterAMarkerThatOpensNoKey")
    void privateKeyBlockAfterAMarkerThatOpensNoKeyIsRedacted(String label, String content) {
        String redacted = SensitiveContentRedactor.redact(content);
        assertFalse(redacted.contains(FAKE_KEY_BODY), redacted);
        assertTrue(redacted.contains("[REDACTED]"), redacted);
        assertEquals(lineSeparatorsOf(content), lineSeparatorsOf(redacted));
        String firstLine = content.substring(0, content.indexOf('\n'));
        if (!firstLine.contains("RSA PRIVATE KEY")) {
            assertTrue(redacted.startsWith(firstLine + "\n"), redacted);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\n", "\r\n", "\r"})
    void markerWhoseClosingDashesAreNotOnItsLineOpensNoKey(String newline) {
        String lines = DASHES + "BEGIN " + newline + "\"PRIVATE KEY" + DASHES + "\"" + newline + "visible";
        assertEquals(lines, SensitiveContentRedactor.redact(lines));

        String withBlock = lines + newline + privateKeyBlock("RSA PRIVATE KEY", newline);
        String redacted = SensitiveContentRedactor.redact(withBlock);
        assertEquals(lines + newline + "[REDACTED]" + newline + newline, redacted);
        assertFalse(redacted.contains(FAKE_KEY_BODY), redacted);
        assertEquals(lineSeparatorsOf(withBlock), lineSeparatorsOf(redacted));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> privateKeyMarkersWithAnotherCaseOrBlank() {
        String nbsp = "\u00A0";
        var markers = java.util.List.of(
                java.util.List.of("minuscules", "begin rsa private key", "end rsa private key"),
                java.util.List.of("casse mêlée", "Begin Rsa Private Key", "End Rsa Private Key"),
                java.util.List.of("tabulation après BEGIN et END", "BEGIN\tRSA PRIVATE KEY", "END\tRSA PRIVATE KEY"),
                java.util.List.of("espace insécable entre les mots", "BEGIN" + nbsp + "RSA" + nbsp + "PRIVATE" + nbsp + "KEY",
                        "END" + nbsp + "RSA" + nbsp + "PRIVATE" + nbsp + "KEY"),
                java.util.List.of("espace insécable au début, espaces à la fin", "BEGIN RSA" + nbsp + "PRIVATE KEY",
                        "END RSA PRIVATE KEY"),
                java.util.List.of("bloc PGP en minuscules", "begin pgp private key block", "end pgp private key block"),
                java.util.List.of("deux espaces entre deux mots", "BEGIN RSA PRIVATE  KEY", "END RSA PRIVATE KEY"),
                java.util.List.of("suites de blancs différentes au début et à la fin",
                        "BEGIN  RSA\t" + nbsp + "PRIVATE KEY", "END \tRSA PRIVATE   KEY"));
        return markers.stream().flatMap(marker -> java.util.stream.Stream.of("\n", "\r\n").map(newline ->
                org.junit.jupiter.params.provider.Arguments.of(marker.get(0) + (newline.length() == 1 ? " (LF)" : " (CRLF)"),
                        marker.get(1), marker.get(2), newline)));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("privateKeyMarkersWithAnotherCaseOrBlank")
    void privateKeyMarkerIsRecognizedWhateverItsCaseAndBlanks(String label, String begin, String end, String newline) {
        String block = DASHES + begin + DASHES + newline + FAKE_KEY_BODY + newline + DASHES + end + DASHES;
        String content = "before" + newline + block + newline + "after";
        String redacted = SensitiveContentRedactor.redact(content);
        assertEquals("before" + newline + "[REDACTED]" + newline + newline + newline + "after", redacted);

        String truncated = "before" + newline + DASHES + begin + DASHES + newline + FAKE_KEY_BODY + newline + "tail";
        assertEquals("before" + newline + "[REDACTED]" + newline + newline, SensitiveContentRedactor.redact(truncated));
    }

    private static final String SECOND_BODY = "MIIsecondbodyNOTAKEY4567";

    private static String endThenBegin(String prefix, String endPrefix, boolean sharedDashes, String newline) {
        String firstEnd = endPrefix + DASHES + "END RSA PRIVATE KEY" + DASHES;
        String secondBegin = (sharedDashes ? "" : DASHES) + "BEGIN EC PRIVATE KEY" + DASHES;
        return prefix + DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + newline
                + prefix + "FIRSTBODY" + newline
                + firstEnd + secondBegin + newline
                + prefix + SECOND_BODY + newline
                + DASHES + "END EC PRIVATE KEY" + DASHES;
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> privateKeyBlocksAfterAnEndOnTheSameLine() {
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String newline : java.util.List.of("\n", "\r\n", "\r")) {
            String label = newline.equals("\n") ? "LF" : newline.equals("\r\n") ? "CRLF" : "CR";
            cases.add(org.junit.jupiter.params.provider.Arguments.of(
                    "END commenté au milieu de la ligne, dix tirets (" + label + ")",
                    endThenBegin("# ", "# ", false, newline)));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(
                    "END précédé d'un x (" + label + ")", endThenBegin("x", "x", false, newline)));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(
                    "END en début de ligne, dix tirets (" + label + ")", endThenBegin("", "", false, newline)));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(
                    "END au milieu de la ligne, tirets partagés (" + label + ")",
                    endThenBegin("# ", "# ", true, newline)));
            cases.add(org.junit.jupiter.params.provider.Arguments.of(
                    "END en début de ligne, tirets partagés (" + label + ")", endThenBegin("", "", true, newline)));
            for (int dashes = 6; dashes <= 9; dashes++) {
                cases.add(org.junit.jupiter.params.provider.Arguments.of(
                        "END en début de ligne, " + dashes + " tirets (" + label + ")",
                        endThenDashesThenBegin("", dashes, newline)));
                cases.add(org.junit.jupiter.params.provider.Arguments.of(
                        "END au milieu de la ligne, " + dashes + " tirets (" + label + ")",
                        endThenDashesThenBegin("x", dashes, newline)));
            }
        }
        return cases.stream();
    }

    /** Bloc RSA dont le END est suivi, sur sa ligne, de {@code dashes} tirets puis de {@code BEGIN EC PRIVATE KEY-----}. */
    private static String endThenDashesThenBegin(String prefix, int dashes, String newline) {
        return prefix + DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + newline
                + prefix + "FIRSTBODY" + newline
                + prefix + DASHES + "END RSA PRIVATE KEY" + "-".repeat(dashes) + "BEGIN EC PRIVATE KEY" + DASHES + newline
                + prefix + SECOND_BODY + newline
                + DASHES + "END EC PRIVATE KEY" + DASHES;
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("privateKeyBlocksAfterAnEndOnTheSameLine")
    void privateKeyBlockAfterAnEndMarkerOnTheSameLineIsRedacted(String label, String content) {
        String redacted = SensitiveContentRedactor.redact(content);
        assertFalse(redacted.contains(SECOND_BODY), redacted);
        assertFalse(redacted.contains("FIRSTBODY"), redacted);
        assertFalse(redacted.contains("EC PRIVATE KEY"), redacted);
        assertEquals(lineSeparatorsOf(content), lineSeparatorsOf(redacted));
    }

    @Test
    void twoKeysOnOneJsonLineAreBothRedacted() {
        String escapedNewline = (char) 92 + "n";
        String first = DASHES + "BEGIN PRIVATE KEY" + DASHES + escapedNewline + "FIRSTBODY" + escapedNewline
                + DASHES + "END PRIVATE KEY" + DASHES + escapedNewline;
        String second = DASHES + "BEGIN PRIVATE KEY" + DASHES + escapedNewline + SECOND_BODY + escapedNewline
                + DASHES + "END PRIVATE KEY" + DASHES + escapedNewline;
        String content = "{\n  \"k1\": \"" + first + "\", \"k2\": \"" + second + "\",\n"
                + "  \"client_email\": \"svc@example.iam.gserviceaccount.com\"\n}\n";
        String redacted = SensitiveContentRedactor.redact(content);
        assertFalse(redacted.contains("FIRSTBODY"), redacted);
        assertFalse(redacted.contains(SECOND_BODY), redacted);
        assertTrue(redacted.contains("  \"client_email\": \"svc@example.iam.gserviceaccount.com\"\n}\n"), redacted);
        assertEquals(lineSeparatorsOf(content), lineSeparatorsOf(redacted));
    }

    @Test
    void beginOfAnotherTypeOnTheEndLineStillHidesTheRestOfTheLine() {
        String content = DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + "\nAAA\nx" + DASHES + "END RSA PRIVATE KEY" + DASHES
                + DASHES + "BEGIN CERTIFICATE" + DASHES + "TAILOFTHELINE\nafter";
        assertEquals("[REDACTED]\n\n\nafter", SensitiveContentRedactor.redact(content));
    }

    @Test
    void endMarkerFollowedByAnIncompleteBeginKeepsClosingAtTheEndOfItsLine() {
        String content = DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + "\nAAA" + DASHES + "END RSA PRIVATE KEY" + DASHES
                + "x " + DASHES + "BEGIN CERT\nvisible";
        assertEquals("[REDACTED]\n\nvisible", SensitiveContentRedactor.redact(content));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> markersSplitAcrossTwoLines() {
        String body = FAKE_KEY_BODY;
        String escapedNewline = (char) 92 + "n";
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("concaténation Java",
                        "String pem = \"" + DASHES + "BEGIN RSA \" +\n\"PRIVATE KEY" + DASHES + escapedNewline + "\" +\n\""
                                + body + escapedNewline + "\" +\n\"" + DASHES + "END RSA PRIVATE KEY" + DASHES + "\";\n"),
                org.junit.jupiter.params.provider.Arguments.of("chaîne Python implicite",
                        "PEM = (\"" + DASHES + "BEGIN RSA \"\n       \"PRIVATE KEY" + DASHES + escapedNewline + "\"\n       \""
                                + body + escapedNewline + "\"\n       \"" + DASHES + "END RSA PRIVATE KEY" + DASHES + "\")\n"),
                org.junit.jupiter.params.provider.Arguments.of("coupure par CRLF",
                        DASHES + "BEGIN RSA\r\nPRIVATE KEY" + DASHES + "\r\n" + body + "\r\n" + DASHES
                                + "END RSA PRIVATE KEY" + DASHES + "\r\n"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("markersSplitAcrossTwoLines")
    void splitMarkerVariantsOpenNoKey(String label, String source) {
        // Limite acceptée et documentée : le marqueur coupé n'ouvre pas de clé, le corps reste en clair.
        assertEquals(source, SensitiveContentRedactor.redact(source));
    }

    @Test
    void generatedBlocksFollowedByAnotherBlockOnTheSameLineNeverLeakABody() {
        String[] types = {"RSA PRIVATE KEY", "EC PRIVATE KEY", "PRIVATE KEY", "PGP PRIVATE KEY BLOCK"};
        String[] prefixes = {"", "# ", "// ", "x", " ", "\"", "x-"};
        String[] newlines = {"\n", "\r\n", "\r"};
        // "shared" suivi de un à quatre tirets : six à neuf tirets entre le type du END et le BEGIN suivant.
        String[] joins = {"\n", "", " ", "SECRET", "\r\n", "shared", "z", DASHES + "BEGIN CERTIFICATE" + DASHES,
                "shared-", "shared--", "shared---", "shared----", "-"};
        var random = new java.util.Random(16);
        int refused = 0;
        for (int sample = 0; sample < 20_000; sample++) {
            int blocks = 1 + random.nextInt(3);
            String newline = newlines[random.nextInt(newlines.length)];
            var content = new StringBuilder();
            boolean shared = false;
            for (int index = 0; index < blocks; index++) {
                String type = types[random.nextInt(types.length)];
                String prefix = prefixes[random.nextInt(prefixes.length)];
                String endPrefix = random.nextBoolean() ? prefix : prefixes[random.nextInt(prefixes.length)];
                content.append(index == 0 ? prefix : "").append(shared ? "" : DASHES).append("BEGIN ").append(type)
                        .append(DASHES).append(newline).append(prefix).append("GENBODY").append(index).append(newline)
                        .append(endPrefix).append(DASHES).append("END ").append(type).append(DASHES);
                shared = false;
                if (index < blocks - 1) {
                    String join = joins[random.nextInt(joins.length)];
                    if (join.startsWith("shared")) {
                        shared = true;
                        content.append(join.substring("shared".length()));
                    } else {
                        content.append(join);
                    }
                }
            }
            String text = content.toString();
            String redacted;
            try {
                redacted = SensitiveContentRedactor.redact(text);
            } catch (OversizedSecretException expected) {
                // Un refus ne publie rien : aucun corps ne fuit. Les refus sont comptés pour que le
                // test ne devienne pas vacant.
                refused++;
                continue;
            }
            for (int index = 0; index < blocks; index++) {
                assertFalse(redacted.contains("GENBODY" + index), text.replace("\r", "\\r").replace("\n", "\\n"));
            }
            assertEquals(lineSeparatorsOf(text), lineSeparatorsOf(redacted));
        }
        assertEquals(0, refused, "contenus générés refusés au lieu d'être masqués");
    }

    @Test
    void markerSplitAcrossTwoSourceLinesOpensNoKey() {
        String source = "String pem = \"" + DASHES + "BEGIN RSA \" +\n"
                + "        \"PRIVATE KEY" + DASHES + "\\n\" +\n"
                + "        \"" + FAKE_KEY_BODY + "\\n\" +\n"
                + "        \"" + DASHES + "END RSA PRIVATE KEY" + DASHES + "\";\n";
        // Limite acceptée : la fin du marqueur n'est pas sur sa ligne, le corps reste en clair.
        assertEquals(source, SensitiveContentRedactor.redact(source));
    }

    @Test
    void codeLiteralEndingWithAKeySuffixMasksTheRestOfTheContent() {
        String source = "if (l.startsWith(\"" + DASHES + "BEGIN \") && l.endsWith(\"PRIVATE KEY" + DASHES + "\")) {}\n"
                + "visible\n";
        // Limite acceptée : la ligne ouvre une clé sans ligne END, la suite est masquée (aucune fuite).
        assertEquals("if (l.startsWith(\"[REDACTED]\n\n", SensitiveContentRedactor.redact(source));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\n", "\r\n"})
    void endMarkerInTheMiddleOfALineClosesTheBlockAtTheEndOfItsLine(String newline) {
        String type = "RSA PRIVATE KEY";
        String endLine = DASHES + "END " + type + DASHES;
        String content = DASHES + "BEGIN " + type + DASHES + newline
                + "AAA" + endLine + "SECRETBODY" + newline
                + endLine + newline + "after";
        String redacted = SensitiveContentRedactor.redact(content);
        assertEquals("[REDACTED]" + newline + newline + endLine + newline + "after", redacted);
        assertFalse(redacted.contains("SECRETBODY"), redacted);

        String withoutSecondEnd = DASHES + "BEGIN " + type + DASHES + newline
                + "AAA" + endLine + "SECRETBODY" + newline + "tail";
        assertEquals("[REDACTED]" + newline + newline + "tail", SensitiveContentRedactor.redact(withoutSecondEnd));
    }

    @Test
    void serviceAccountKeyWithEscapedNewlinesHidesOnlyItsLine() {
        String escapedNewline = (char) 92 + "n";
        String key = DASHES + "BEGIN PRIVATE KEY" + DASHES + escapedNewline + FAKE_KEY_BODY + escapedNewline
                + DASHES + "END PRIVATE KEY" + DASHES + escapedNewline;
        String content = "{\n  \"type\": \"service_account\",\n  \"private_key\": \"" + key + "\",\n"
                + "  \"client_email\": \"svc@example.iam.gserviceaccount.com\"\n}\n";
        String redacted = SensitiveContentRedactor.redact(content);
        assertFalse(redacted.contains(FAKE_KEY_BODY), redacted);
        assertTrue(redacted.contains("  \"client_email\": \"svc@example.iam.gserviceaccount.com\"\n}\n"), redacted);
        assertEquals(lineSeparatorsOf(content), lineSeparatorsOf(redacted));
    }

    @Test
    void javaConcatenatedKeyHidesUpToTheEndOfItsEndLine() {
        String escapedNewline = (char) 92 + "n";
        String content = "String pem = \"" + DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + escapedNewline + "\"\n"
                + "        + \"" + FAKE_KEY_BODY + escapedNewline + "\"\n"
                + "        + \"" + DASHES + "END RSA PRIVATE KEY" + DASHES + "\";\n"
                + "int after = 1;\n";
        assertEquals("String pem = \"[REDACTED]\n\n\nint after = 1;\n", SensitiveContentRedactor.redact(content));
    }

    @Test
    void commentedKeyHidesUpToTheEndOfItsEndLine() {
        String content = "// " + DASHES + "BEGIN RSA PRIVATE KEY" + DASHES + "\n// " + FAKE_KEY_BODY + "\n// "
                + DASHES + "END RSA PRIVATE KEY" + DASHES + "\nvisible\n";
        assertEquals("// [REDACTED]\n\n\nvisible\n", SensitiveContentRedactor.redact(content));
    }

    @Test
    void endMarkerPrecededByBlanksOnItsLineClosesTheBlock() {
        String type = "RSA PRIVATE KEY";
        String content = DASHES + "BEGIN " + type + DASHES + "\n" + FAKE_KEY_BODY + "\n"
                + " \t" + (char) 0x00A0 + DASHES + "END " + type + DASHES + "\nafter";
        assertEquals("[REDACTED]\n\n\nafter", SensitiveContentRedactor.redact(content));
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> unrecognizedPrivateKeyMarkers() {
        String four = "-".repeat(4);
        String enDashes = "\u2013".repeat(5);
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("quatre tirets",
                        four + "BEGIN RSA PRIVATE KEY" + four + "\n" + FAKE_KEY_BODY + "\n" + four + "END RSA PRIVATE KEY" + four),
                org.junit.jupiter.params.provider.Arguments.of("espace avant les tirets de fin",
                        DASHES + "BEGIN RSA PRIVATE KEY " + DASHES + "\n" + FAKE_KEY_BODY + "\n" + DASHES + "END RSA PRIVATE KEY " + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("BEGIN collé au type",
                        DASHES + "BEGINRSA PRIVATE KEY" + DASHES + "\n" + FAKE_KEY_BODY + "\n" + DASHES + "ENDRSA PRIVATE KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("tirets demi-cadratin",
                        enDashes + "BEGIN RSA PRIVATE KEY" + enDashes + "\n" + FAKE_KEY_BODY + "\n" + enDashes + "END RSA PRIVATE KEY" + enDashes),
                org.junit.jupiter.params.provider.Arguments.of("E cyrillique dans BEGIN",
                        DASHES + "B\u0415GIN RSA PRIVATE KEY" + DASHES + "\n" + FAKE_KEY_BODY + "\n" + DASHES + "\u0415ND RSA PRIVATE KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("cadratin U+2003 entre PRIVATE et KEY",
                        DASHES + "BEGIN RSA PRIVATE\u2003KEY" + DASHES + "\n" + FAKE_KEY_BODY + "\n" + DASHES + "END RSA PRIVATE\u2003KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("espace idéographique U+3000 entre PRIVATE et KEY",
                        DASHES + "BEGIN RSA PRIVATE\u3000KEY" + DASHES + "\n" + FAKE_KEY_BODY + "\n" + DASHES + "END RSA PRIVATE\u3000KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("espace sans chasse U+200B après BEGIN",
                        DASHES + "BEGIN\u200BRSA PRIVATE KEY" + DASHES + "\n" + FAKE_KEY_BODY + "\n" + DASHES + "END\u200BRSA PRIVATE KEY" + DASHES),
                org.junit.jupiter.params.provider.Arguments.of("format PuTTY",
                        "PuTTY-User-Key-File-3: ssh-rsa\nEncryption: none\nComment: fixture\nPublic-Lines: 1\nAAAAB3NzaC1yc2E\n"
                                + "Private-Lines: 1\n" + FAKE_KEY_BODY + "\nPrivate-MAC: 00"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("unrecognizedPrivateKeyMarkers")
    void unrecognizedPrivateKeyMarkerFormsStayInClear(String label, String content) {
        // Limites connues : ces variantes ne sont pas reconnues comme blocs de clé privée.
        assertEquals(content, SensitiveContentRedactor.redact(content));
    }

    @Test
    void privateKeyBlockRenderingWithoutTheV9DefectsIsUnchanged() throws ReflectiveOperationException {
        String rsa = privateKeyBlock("RSA PRIVATE KEY", "\n");
        java.util.List<String> contents = java.util.List.of(
                rsa,
                "before\n" + rsa + "\nafter",
                privateKeyBlock("CERTIFICATE", "\n") + "\n" + rsa,
                "x " + DASHES + "BEGIN foo\nbar",
                "before\n" + DASHES + "BEGIN PRIVATE KEY" + DASHES + "\nabc\nstill-secret\n",
                rsa + "sk-" + syntheticAlphanumeric(255) + " y");
        for (String content : contents) {
            V9Rendering reference = v9ReplacePrivateKeyBlocks(content);
            assertFalse(reference.defective(), content);
            assertEquals(reference.output(), replacePrivateKeyBlocks(content), content);
        }
        assertEquals("x " + DASHES + "BEGIN foo\nbar", SensitiveContentRedactor.redact("x " + DASHES + "BEGIN foo\nbar"));
    }

    @Test
    void generatedPrivateKeyMarkerContentsRenderAsV9WhenV9HasNoDefect() throws ReflectiveOperationException {
        String[] parts = {DASHES + "BEGIN ", DASHES + "END ", "RSA PRIVATE KEY" + DASHES, "PRIVATE KEY" + DASHES,
                "PGP PRIVATE KEY BLOCK" + DASHES, "CERTIFICATE" + DASHES, DASHES, "-", "BEGIN ", "\n", "\r\n", "\r",
                "MII", "x", "a b"};
        var random = new java.util.Random(15);
        int compared = 0;
        for (int sample = 0; sample < 6000; sample++) {
            var content = new StringBuilder();
            int length = 1 + random.nextInt(30);
            for (int index = 0; index < length; index++) {
                content.append(parts[random.nextInt(parts.length)]);
            }
            String text = content.toString();
            V9Rendering reference = v9ReplacePrivateKeyBlocks(text);
            String rendered = replacePrivateKeyBlocks(text);
            assertEquals(lineSeparatorsOf(text), lineSeparatorsOf(rendered), text);
            if (!reference.defective()) {
                assertEquals(reference.output(), rendered, text);
                compared++;
            }
        }
        assertTrue(compared > 1000, "cas comparés : " + compared);
    }

    private static String replacePrivateKeyBlocks(String content) throws ReflectiveOperationException {
        var method = SensitiveContentRedactor.class.getDeclaredMethod("replacePrivateKeyBlocks", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, content);
    }

    /**
     * Rendu de la boucle {@code replacePrivateKeyBlocks} de {@code secret-redaction-v9} ;
     * {@code defective} signale l'un de ses deux défauts : fin de marqueur cherchée au-delà de sa ligne,
     * marqueur sans clé dont la reprise en fin de marqueur saute un {@code -----BEGIN } commencé avant, ou
     * {@code -----END} au milieu d'une ligne suivi d'un texte laissé visible sur cette ligne.
     */
    private record V9Rendering(String output, boolean defective) {
    }

    /** Copie de référence de la boucle v9, instrumentée pour signaler ses défauts. */
    private static V9Rendering v9ReplacePrivateKeyBlocks(String content) {
        String begin11 = DASHES + "BEGIN ";
        String endPrefix = DASHES + "END ";
        boolean defective = false;
        StringBuilder output = new StringBuilder(content.length());
        int cursor = 0;
        while (cursor < content.length()) {
            int begin = content.indexOf(begin11, cursor);
            if (begin < 0) {
                output.append(content, cursor, content.length());
                break;
            }
            int markerEnd = content.indexOf(DASHES, begin + begin11.length());
            if (markerEnd < 0) {
                output.append(content, cursor, content.length());
                break;
            }
            markerEnd += 5;
            String beginMarker = content.substring(begin, markerEnd);
            if (beginMarker.indexOf('\r') >= 0 || beginMarker.indexOf('\n') >= 0) {
                defective = true;
            }
            if (!beginMarker.endsWith("PRIVATE KEY" + DASHES) && !beginMarker.endsWith("PRIVATE KEY BLOCK" + DASHES)) {
                int nextBegin = content.indexOf(begin11, begin + begin11.length());
                if (nextBegin >= 0 && nextBegin < markerEnd) {
                    defective = true;
                }
                output.append(content, cursor, markerEnd);
                cursor = markerEnd;
                continue;
            }
            String keyType = beginMarker.substring(begin11.length(), beginMarker.length() - 5);
            String endMarker = endPrefix + keyType + DASHES;
            int end = content.indexOf(endMarker, markerEnd);
            if (end >= 0 && !startsItsLine(content, end)) {
                int afterEnd = end + endMarker.length();
                if (afterEnd < content.length() && content.charAt(afterEnd) != '\r' && content.charAt(afterEnd) != '\n') {
                    defective = true;
                }
            }
            if (end < 0) {
                output.append(content, cursor, begin).append("[REDACTED]");
                appendV9LineSeparators(output, content, begin, content.length());
                cursor = content.length();
                continue;
            }
            end += endMarker.length();
            output.append(content, cursor, begin).append("[REDACTED]");
            appendV9LineSeparators(output, content, begin, end);
            cursor = end;
        }
        return new V9Rendering(output.toString(), defective);
    }

    /** Seuls des tirets ou des blancs précèdent {@code position} sur sa ligne. */
    private static boolean startsItsLine(String content, int position) {
        for (int index = position - 1; index >= 0; index--) {
            char character = content.charAt(index);
            if (character == '\r' || character == '\n') {
                return true;
            }
            if (character != '-' && character != ' ' && character != '\t' && character != (char) 0x00A0) {
                return false;
            }
        }
        return true;
    }

    private static void appendV9LineSeparators(StringBuilder output, String content, int begin, int end) {
        for (int index = begin; index < end; index++) {
            char character = content.charAt(index);
            if (character == '\r' || character == '\n') {
                output.append(character);
            }
        }
    }

    /** Seuil du sprint 14 : 1 Mo de débuts forgés redigé en moins d'une seconde. */
    private static final java.time.Duration FORGED_REPETITION_BUDGET = java.time.Duration.ofSeconds(1);
    private static final int ONE_MEGABYTE = 1 << 20;
    /** Motifs {@code JWT} et {@code XML_SECRET_ELEMENT} de {@code secret-redaction-v8}, référence de non-régression. */
    private static final java.util.regex.Pattern V8_JWT = java.util.regex.Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,4096}+\\.[A-Za-z0-9_-]{10,4096}+\\.[A-Za-z0-9_-]{10,4096}+\\b");
    private static final java.util.regex.Pattern V8_XML_SECRET_ELEMENT = java.util.regex.Pattern.compile(
            "(?i)(<((?:[A-Za-z0-9_-]++[._-])*+(?:password|passwd|pwd|token|secret|api[_-]?key"
                    + "|client[_-]?secret|access[_-]?key|access[_-]?token|private[_-]?key))"
                    + "(?:[ \\t][^<>\\r\\n]{0,256}+)?+>)(?:<!\\[CDATA\\[[^\\]\\r\\n]{1,4096}+]]>"
                    + "|[^<\\r\\n]{1,4096}+)(</\\2>)");
    /** JWT synthétique assemblé à l'exécution : trois segments base64url, sans signature réelle. */
    private static final String JWT_SEGMENTS = String.join(".", "ey" + "J" + syntheticAlphanumeric(17),
            "ey" + "J" + syntheticAlphanumeric(24), syntheticAlphanumeric(26));

    @Test
    void forgedRepetitionOfJwtStartsIsRedactedUnchangedInUnderOneSecond() {
        String content = "eyJ-".repeat(ONE_MEGABYTE / 4);
        assertEquals(262_144, content.length() / 4);
        SensitiveContentRedactor.redact("eyJ-".repeat(1024));
        String rendered = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(FORGED_REPETITION_BUDGET,
                () -> SensitiveContentRedactor.redact(content));
        assertEquals(content, rendered);
    }

    @Test
    void forgedRepetitionOfCdataOpeningsIsRedactedUnchangedInUnderOneSecond() {
        String opening = "<password><![CDATA[";
        String content = opening.repeat(ONE_MEGABYTE / opening.length() + 1);
        assertTrue(content.length() >= ONE_MEGABYTE);
        SensitiveContentRedactor.redact(opening.repeat(1024));
        String rendered = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(FORGED_REPETITION_BUDGET,
                () -> SensitiveContentRedactor.redact(content));
        assertEquals(content, rendered);
    }

    @Test
    void jwtIsRedactedWhateverSeparatorPrecedesIt() {
        assertEquals("jwt [REDACTED]", SensitiveContentRedactor.redact("jwt " + JWT_SEGMENTS));
        String afterHyphen = SensitiveContentRedactor.redact("id-" + JWT_SEGMENTS);
        assertEquals("id-[REDACTED]", afterHyphen);
        for (String segment : JWT_SEGMENTS.split("\\.")) {
            assertFalse(afterHyphen.contains(segment), afterHyphen);
        }
        assertEquals("<password>[REDACTED]</password>",
                SensitiveContentRedactor.redact("<password><![CDATA[a<b-secret]]></password>"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "-", "=", ":", "id-", "x ", "a", "_"})
    void jwtAndCdataRenderingMatchesTheV8Patterns(String before) {
        String sharedRun = "-eyJ" + "a".repeat(2000) + "-eyJ" + "b".repeat(3000) + ".cccccccccccc.dddddddddddd";
        String wordThenHyphen = "aeyJ" + "q".repeat(20) + "-eyJ" + "r".repeat(20) + ".ssssssssssss.tttttttttttt";
        String longSecond = "eyJ" + "u".repeat(12) + "." + "v".repeat(5000) + ".wwwwwwwwwwww";
        for (String content : java.util.List.of(before + JWT_SEGMENTS, sharedRun, wordThenHyphen, longSecond,
                before + JWT_SEGMENTS + "-" + JWT_SEGMENTS,
                "<password><![CDATA[a<b-secret]]></password>",
                "<password><![CDATA[x <password>v</password>",
                "<PASSWORD><![cdata[mixed]]></Password> <token>t</TOKEN>")) {
            assertEquals(renderedByV8Patterns(content), SensitiveContentRedactor.redact(content), content);
        }
        assertEquals("<password><![CDATA[x <password>[REDACTED]</password>",
                SensitiveContentRedactor.redact("<password><![CDATA[x <password>v</password>"));
    }

    @Test
    void generatedJwtAndCdataContentsRenderAsWithTheV8Patterns() {
        String[] jwtParts = {"eyJ", "eyJ", "-", ".", "aaaaaaaaaa", "b", "_", " ", "0", "=", "\n"};
        String[] xmlParts = {"<password>", "</password>", "<![CDATA[", "<![cdata[", "]]>", "]", "x", "<", "\n",
                "<Token a=\"1\">", "</TOKEN>", "yyyyyy",
                // Repli de casse Unicode exclu, comme (?i) sans UNICODE_CASE : s long, signe Kelvin, i sans point.
                "</pa\u017f\u017fword>", "</to\u212aen>", "<![CDATA[\u0131"};
        var random = new java.util.Random(14);
        for (int sample = 0; sample < 4000; sample++) {
            String[] parts = sample % 2 == 0 ? jwtParts : xmlParts;
            var content = new StringBuilder();
            int length = 1 + random.nextInt(60);
            for (int index = 0; index < length; index++) {
                content.append(parts[random.nextInt(parts.length)]);
            }
            String text = content.toString();
            assertEquals(renderedByV8Patterns(text), SensitiveContentRedactor.redact(text), text);
        }
    }

    private static String renderedByV8Patterns(String content) {
        String jwt = V8_JWT.matcher(content).replaceAll("[REDACTED]");
        return V8_XML_SECRET_ELEMENT.matcher(jwt).replaceAll("$1[REDACTED]$3");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"db-password", "db_password", "my-token",
            "app.db-password", "api_key", "api-key", "access_key", "private_key", "client_secret", "db.password"})
    void xmlElementWhoseNameEndsWithASensitiveKeyIsRedacted(String name) {
        String content = "<" + name + ">hunter2-value</" + name + ">";
        assertEquals("<" + name + ">[REDACTED]</" + name + ">", SensitiveContentRedactor.redact(content));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tokenizer", "mytoken", "password_hint"})
    void xmlElementWhoseNameDoesNotEndWithASensitiveKeyIsKept(String name) {
        String content = "<" + name + ">ordinary-value</" + name + ">";
        assertEquals(content, SensitiveContentRedactor.redact(content));
    }

    @Test
    void compositeXmlNameValueIsRedactedAtTheBoundAndRefusedJustAbove() {
        String atBound = SensitiveContentRedactor.redact("<db-password>" + "v".repeat(4096) + "</db-password>");
        assertEquals("<db-password>[REDACTED]</db-password>", atBound);
        String aboveBound = "<db-password>" + "v".repeat(4097) + "</db-password>";
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(aboveBound));
        assertFalse(failure.getMessage().contains("vvvv"), failure.getMessage());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"--api-key", "--private-key", "--access-key",
            "--db-password", "--api_key", "-token"})
    void optionWhoseNameEndsWithASensitiveKeyIsRedacted(String option) {
        assertEquals("run " + option + " [REDACTED]", SensitiveContentRedactor.redact("run " + option + " hunter2-value"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"--password-file conf.txt", "--tokenizer simple"})
    void optionWhoseNameDoesNotEndWithASensitiveKeyIsKept(String content) {
        assertEquals(content, SensitiveContentRedactor.redact(content));
    }

    @Test
    void compositeNameOfHalfAMillionComponentsHasBoundedCost() {
        String name = "a-".repeat(499_999) + "password";
        String content = "<" + name + ">hunter2-value</" + name + ">\nrun --" + name + " hunter2-value";
        // Garde-fou contre une récursion ou un retour arrière sur le nom, pas une mesure de performance.
        String rendered = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> SensitiveContentRedactor.redact(content));
        assertEquals("<" + name + ">[REDACTED]</" + name + ">\nrun --" + name + " [REDACTED]", rendered);
    }

    @Test
    void structuredTokenPrefixFilterKeepsEveryPassResult() throws ReflectiveOperationException {
        var tokens = redactorList("STRUCTURED_TOKENS").stream().map(java.util.regex.Pattern.class::cast).toList();
        var prefixes = redactorList("STRUCTURED_TOKEN_PREFIXES").stream()
                .map(family -> ((java.util.List<?>) family).stream().map(String.class::cast).toList())
                .toList();
        assertEquals(tokens.size(), prefixes.size());
        var contents = new java.util.ArrayList<String>();
        SecretRedactionProbeTest.probes().forEach(probe -> contents.add(probe.input()));
        String body = syntheticAlphanumeric(255);
        for (String prefix : java.util.List.of("sk-", "sk-proj-", "xoxb-", "glpat-", "ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_",
                "sk_live_", "rk_test_")) {
            contents.add("key = " + prefix + body);
            contents.add("id-" + prefix + body + "-tail");
            contents.add(PRIVATE_KEY_BLOCK + prefix + body);
        }
        contents.add("AKIA" + "ABCDEFGHIJKLMNOP" + " ASIA" + "QRSTUVWXYZ012345 npm_" + syntheticAlphanumeric(36)
                + " AIza" + syntheticAlphanumeric(35));
        for (String content : contents) {
            String unfiltered = content;
            String filtered = content;
            for (int index = 0; index < tokens.size(); index++) {
                unfiltered = tokens.get(index).matcher(unfiltered).replaceAll("[REDACTED]");
                String current = filtered;
                if (prefixes.get(index).stream().anyMatch(current::contains)) {
                    filtered = tokens.get(index).matcher(filtered).replaceAll("[REDACTED]");
                }
            }
            assertEquals(unfiltered, filtered, content);
        }
    }

    private static java.util.List<?> redactorList(String name) throws ReflectiveOperationException {
        var field = SensitiveContentRedactor.class.getDeclaredField(name);
        field.setAccessible(true);
        return (java.util.List<?>) field.get(null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"<password>hunter'2isTheSecret</password>",
            "token=abc\"defSECRET\"", "ab'cdSECRET'"})
    void unquotedOptionValueContainingAQuoteIsRedactedUpToTheNextBlank(String value) {
        for (String option : java.util.List.of("--api-key", "--db-password")) {
            assertEquals("run " + option + " [REDACTED] next",
                    SensitiveContentRedactor.redact("run " + option + " " + value + " next"));
        }
        String aboveBound = "run --api-key ab'" + "s".repeat(5_000);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(aboveBound));
        assertFalse(failure.getMessage().contains("ssss"), failure.getMessage());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"password", "db-password"})
    void xmlValueAboveTheBoundIsRefusedEvenWhenItContainsABracket(String name) {
        String aboveBound = "<" + name + ">aaa]" + "b".repeat(5_000) + "</" + name + ">";
        var failure = org.junit.jupiter.api.Assertions.assertThrows(OversizedSecretException.class,
                () -> SensitiveContentRedactor.redact(aboveBound));
        assertFalse(failure.getMessage().contains("bbbb"), failure.getMessage());
        String atBound = "<" + name + ">aaa]" + "b".repeat(4_092) + "</" + name + ">";
        assertEquals("<" + name + ">[REDACTED]</" + name + ">", SensitiveContentRedactor.redact(atBound));
    }
}
