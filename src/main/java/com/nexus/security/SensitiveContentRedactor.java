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
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN ";
    private static final String PRIVATE_KEY_END = "-----END ";
    private static final String PRIVATE_KEY_SUFFIX = "PRIVATE KEY-----";
    private static final int MAX_SECRET_CHARS = 4096;
    private static final int MAX_URI_USER_CHARS = 1024;

    private static final Pattern STRUCTURED_TOKEN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9]{20,255}+|github_pat_[A-Za-z0-9_]{20,255}+|(?:AKIA|ASIA)[0-9A-Z]{16})\\b");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\."
                    + "[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\."
                    + "[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\b");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?im)(\\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|password|passwd|secret)"
                    + "\\b\\s{0,32}+[:=]\\s{0,32}+)([\"']?)([A-Za-z0-9+/=_-]{8," + MAX_SECRET_CHARS + "}+)([\"']?)");
    private static final Pattern URI_CREDENTIAL = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]{0,31}+://[^\\s/:@]{1," + MAX_URI_USER_CHARS + "}+:)"
                    + "([^\\s/@]{3," + MAX_SECRET_CHARS + "}+)(@)");

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
        StringBuilder output = new StringBuilder(content.length());
        int cursor = 0;
        while (cursor < content.length()) {
            int begin = content.indexOf(PRIVATE_KEY_BEGIN, cursor);
            if (begin < 0) {
                output.append(content, cursor, content.length());
                break;
            }

            int markerEnd = content.indexOf("-----", begin + PRIVATE_KEY_BEGIN.length());
            if (markerEnd < 0) {
                output.append(content, cursor, content.length());
                break;
            }
            markerEnd += 5;
            String beginMarker = content.substring(begin, markerEnd);
            if (!beginMarker.endsWith(PRIVATE_KEY_SUFFIX)) {
                output.append(content, cursor, markerEnd);
                cursor = markerEnd;
                continue;
            }

            String keyType = beginMarker.substring(PRIVATE_KEY_BEGIN.length(), beginMarker.length() - 5);
            String endMarker = PRIVATE_KEY_END + keyType + "-----";
            int end = content.indexOf(endMarker, markerEnd);
            if (end < 0) {
                output.append(content, cursor, content.length());
                break;
            }
            end += endMarker.length();

            output.append(content, cursor, begin).append(REDACTED);
            appendLineSeparators(output, content, begin, end);
            cursor = end;
        }
        return output.toString();
    }

    private static void appendLineSeparators(StringBuilder output, String content, int begin, int end) {
        for (int index = begin; index < end; index++) {
            char character = content.charAt(index);
            if (character == '\r' || character == '\n') {
                output.append(character);
            }
        }
    }

    private static String replaceSecretAssignments(String content) {
        Matcher matcher = SECRET_ASSIGNMENT.matcher(content);
        StringBuilder output = new StringBuilder(content.length());
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
