package com.nexus.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonde de 35 cas de l'audit du 2026-10-17 (AUD-SEC-01), versionnée : les 19 formats retenus sont
 * redigés, les formats déjà couverts le restent, les identifiants ordinaires sont intacts, et les
 * deux formats hors périmètre de {@code docs/developer/secret-redaction.md} sont épinglés comme
 * limite connue.
 */
class SecretRedactionProbeTest {

    private static final String REDACTED = "[REDACTED]";

    enum Expectation { REDACTED, INTACT, KNOWN_LIMIT }

    record Probe(String name, String input, String secret, Expectation expectation, boolean auditFormat) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Probe> probes() {
        return PROBES.stream();
    }

    private static final List<Probe> PROBES = List.of(
            // Les 19 formats retenus par AUD-SEC-01.
            format("en-tête Authorization Bearer", "Authorization: Bearer abcDEF0123456789ghiJKL0123", "abcDEF0123456789ghiJKL0123"),
            format("assignation token=", "token=tok0123456789secretvalue", "tok0123456789secretvalue"),
            format("clé JSON token", "{\"token\": \"json-token-secret-0123\"}", "json-token-secret-0123"),
            format("clé YAML private_key", "private_key: pk-yaml-secret-0123456", "pk-yaml-secret-0123456"),
            format("assignation pwd=", "pwd=pwd-secret-value-0123", "pwd-secret-value-0123"),
            format("clé credentials", "credentials: cred-secret-value-0123", "cred-secret-value-0123"),
            format("champ secretKey", "String secretKey = \"camel-secret-value-0123\";", "camel-secret-value-0123"),
            format("assignation access_key=", "access_key=access-secret-value-0123", "access-secret-value-0123"),
            format("assignation auth=", "auth=auth-secret-value-0123", "auth-secret-value-0123"),
            format("option --password", "mysql --password hunter2-cli-secret-0123 --user app", "hunter2-cli-secret-0123"),
            format("balise XML password", "<password>xml-secret-value-0123</password>", "xml-secret-value-0123"),
            format("jeton sk-proj-", "key = '%s'", synthetic("sk-", "proj-", "abcdefghijklmnopqrstuvwxyz012345")),
            format("jeton Stripe sk_live_", "stripe(%s)", synthetic("sk_", "live_", "abcdefghijklmnopqrstuvwx")),
            format("jeton Slack xoxb-", "slack %s", synthetic("xox", "b-", "123456789012-123456789012-abcdefghijklmnopqrstuvwx")),
            format("clé Google AIza", "maps %s", synthetic("AI", "za", "SyA1bcdefghijklmnopqrstuvwxyz123456")),
            format("jeton GitLab glpat-", "gitlab %s", synthetic("gl", "pat-", "abcdefghijklmnopqrst")),
            format("jeton npm_", "npm %s", synthetic("np", "m_", "abcdefghijklmnopqrstuvwxyz0123456789")),
            format("jeton dans une URL", "git clone https://%s@github.com/org/repo", "ghtoken0123456789abcdefgh"),
            format("bloc PGP PRIVATE KEY BLOCK",
                    privateKeyBlock("PGP PRIVATE KEY BLOCK", "%s"), "lQOYBGPGPsecretbody0123"),
            // Formats déjà couverts avant AUD-SEC-01.
            covered("jeton GitHub ghp_", "%s", synthetic("gh", "p_", "abcdefghijklmnopqrstuvwxyz0123456789")),
            covered("jeton github_pat_", "%s", synthetic("github", "_pat_", "abcdefghijklmnopqrstuv_0123456789")),
            covered("clé AWS AKIA", "aws %s", synthetic("AK", "IA", "ABCDEFGHIJKLMNOP")),
            covered("JWT", "jwt %s", synthetic("eyJ", "hbGciOiJIUzI1NiJ9.", "eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop")),
            covered("assignation password", "password = \"%s\"", "covered-password-0123"),
            covered("clé api_key", "api_key: %s", "covered-api-key-0123"),
            covered("assignation client_secret", "client_secret=%s", "covered-client-secret-0123"),
            covered("bloc RSA PRIVATE KEY", privateKeyBlock("RSA PRIVATE KEY", "%s"), "MIIcoveredrsabody0123"),
            covered("identifiants user:pass dans une URI", "postgres://app:%s@db/app", "covered-uri-pass-0123"),
            // Cas négatifs : aucune valeur assignée, rien à masquer.
            intact("identifiant tokenizer", "Tokenizer tokenizer = new Tokenizer();"),
            intact("clé author", "author: \"Jane Doe\""),
            intact("appel auth()", "if (auth()) { return; }"),
            intact("prose sur les jetons", "the token budget is 2000 tokens per request"),
            intact("clé max_tokens", "max_tokens = 4096"),
            // Hors périmètre (docs/developer/secret-redaction.md) : limite connue épinglée.
            knownLimit("scalaire YAML de bloc", "password: |\n  yaml-block-secret-0123\n", "yaml-block-secret-0123"),
            knownLimit("valeur sur la ligne suivante", "\"password\":\n  \"next-line-secret-0123\"", "next-line-secret-0123"));

    @Test
    void probeHasThirtyFiveCasesIncludingTheNineteenAuditFormats() {
        assertEquals(35, PROBES.size());
        assertEquals(19, PROBES.stream().filter(Probe::auditFormat).count());
        assertEquals(2, PROBES.stream().filter(probe -> probe.expectation() == Expectation.KNOWN_LIMIT).count());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    void redactsInScopeFormatsAndLeavesOrdinaryCodeIntact(Probe probe) {
        String redacted = SensitiveContentRedactor.redact(probe.input());
        switch (probe.expectation()) {
            case REDACTED -> {
                assertFalse(redacted.contains(probe.secret()), redacted);
                assertTrue(redacted.contains(REDACTED), redacted);
                assertEquals(probe.input().chars().filter(c -> c == '\n').count(),
                        redacted.chars().filter(c -> c == '\n').count(), "séparateurs de lignes préservés");
            }
            case INTACT -> assertEquals(probe.input(), redacted);
            case KNOWN_LIMIT -> assertTrue(redacted.contains(probe.secret()),
                    "limite connue documentée : si ce format est désormais redigé, mettre à jour la sonde et secret-redaction.md");
        }
    }

    /** {@code template} contient la valeur en clair ou le marqueur {@code %s} qui la reçoit. */
    private static Probe format(String name, String template, String secret) {
        return new Probe(name, template.replace("%s", secret), secret, Expectation.REDACTED, true);
    }

    private static Probe covered(String name, String template, String secret) {
        return new Probe(name, template.replace("%s", secret), secret, Expectation.REDACTED, false);
    }

    /**
     * Valeur synthétique assemblée à l'exécution : le source ne contient aucun littéral au format
     * d'un vrai jeton, que les scanners de secrets du dépôt et de l'hébergeur prendraient pour une fuite.
     */
    private static String synthetic(String prefix, String marker, String body) {
        return prefix + marker + body;
    }

    private static String privateKeyBlock(String type, String body) {
        String dashes = "-".repeat(5);
        return dashes + "BEGIN " + type + dashes + "\n" + body + "\n" + dashes + "END " + type + dashes;
    }

    private static Probe intact(String name, String input) {
        return new Probe(name, input, null, Expectation.INTACT, false);
    }

    private static Probe knownLimit(String name, String input, String secret) {
        return new Probe(name, input, secret, Expectation.KNOWN_LIMIT, false);
    }
}
