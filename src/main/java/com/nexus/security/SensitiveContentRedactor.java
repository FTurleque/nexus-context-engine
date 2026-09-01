package com.nexus.security;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redaction conservatrice de secrets à forte confiance avant qu'un contenu source ne quitte
 * la frontière locale de confiance ou ne soit renvoyé comme contexte à un client.
 *
 * <p>La détection privilégie les formats fortement structurés afin d'éviter de transformer
 * arbitrairement le code utilisateur. Elle ne remplace pas un scanner de secrets spécialisé,
 * mais empêche les fuites accidentelles les plus courantes (clés privées, tokens structurés,
 * mots de passe/secrets littéraux et credentials dans une URI). Les redactions multilignes
 * préservent les séparateurs de lignes afin de ne jamais décaler les ranges source persistés.</p>
 */
public final class SensitiveContentRedactor {

    private static final String REDACTED = "[REDACTED]";

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----[\\s\\S]*?-----END(?: [A-Z0-9]+)? PRIVATE KEY-----");
    private static final Pattern STRUCTURED_TOKEN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9]{20,255}|github_pat_[A-Za-z0-9_]{20,255}|(?:AKIA|ASIA)[0-9A-Z]{16})\\b");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?im)(\\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|password|passwd|secret)"
                    + "\\b\\s*[:=]\\s*)([\"']?)([A-Za-z0-9+/=_-]{8,})([\"']?)");
    private static final Pattern URI_CREDENTIAL = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s/:@]+:)([^\\s/@]{3,})(@)");

    private SensitiveContentRedactor() {
    }

    public static String redact(String content) {
        Objects.requireNonNull(content, "content");
        String redacted = replacePrivateKeyBlocks(content);
        redacted = STRUCTURED_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        redacted = replaceSecretAssignments(redacted);
        return URI_CREDENTIAL.matcher(redacted).replaceAll("$1" + REDACTED + "$3");
    }

    private static String replacePrivateKeyBlocks(String content) {
        Matcher matcher = PRIVATE_KEY_BLOCK.matcher(content);
        StringBuffer output = new StringBuffer(content.length());
        while (matcher.find()) {
            String block = matcher.group();
            StringBuilder replacement = new StringBuilder(REDACTED);
            for (int index = 0; index < block.length(); index++) {
                char character = block.charAt(index);
                if (character == '\r' || character == '\n') {
                    replacement.append(character);
                }
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String replaceSecretAssignments(String content) {
        Matcher matcher = SECRET_ASSIGNMENT.matcher(content);
        StringBuffer output = new StringBuffer(content.length());
        while (matcher.find()) {
            String replacement = matcher.group(1)
                    + matcher.group(2)
                    + REDACTED
                    + matcher.group(4);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
