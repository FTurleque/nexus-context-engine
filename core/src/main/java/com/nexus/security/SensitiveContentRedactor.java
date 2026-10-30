package com.nexus.security;

import java.util.Objects;
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

    /** Version persistée dans les index dérivés ; toute évolution impose leur reconstruction. */
    public static final String POLICY_VERSION = "secret-redaction-v6";

    private static final String REDACTED = "[REDACTED]";
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN ";
    private static final String PRIVATE_KEY_END = "-----END ";
    private static final String PRIVATE_KEY_SUFFIX = "PRIVATE KEY-----";
    private static final String PGP_PRIVATE_KEY_SUFFIX = "PRIVATE KEY BLOCK-----";
    private static final int MAX_SECRET_CHARS = 4096;
    private static final int MAX_URI_USER_CHARS = 1024;

    /**
     * Jetons à préfixe fixe : GitHub, AWS, OpenAI/Anthropic ({@code sk-}), Stripe, Slack, Google,
     * GitLab et npm. Chaque motif borne sa longueur et utilise des quantificateurs possessifs.
     */
    private static final Pattern STRUCTURED_TOKEN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9]{20,255}+|github_pat_\\w{20,255}+|(?:AKIA|ASIA)[0-9A-Z]{16}"
                    + "|sk-(?:proj-|ant-)?+[A-Za-z0-9_-]{20,255}+|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,255}+"
                    + "|xox[abposr]-[A-Za-z0-9-]{10,255}+|AIza[0-9A-Za-z_-]{35}"
                    + "|glpat-[A-Za-z0-9_-]{20,255}+|npm_[A-Za-z0-9]{36})(?![A-Za-z0-9_-])");
    /** Schéma {@code Bearer} d'un en-tête HTTP, quelle que soit la clé qui le porte. */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(Bearer[ \\t]{1,8}+)[A-Za-z0-9._~+/-]{16," + MAX_SECRET_CHARS + "}+=*+");
    /** Option de ligne de commande suivie de sa valeur, séparée par des espaces. */
    private static final Pattern PASSWORD_OPTION = Pattern.compile(
            "(?i)(?<![\\w-])(--?(?:password|passwd|pwd|token|secret|api-key)[ \\t]{1,8}+)"
                    + "(?![-\\s])[^\\s\"'`]{1," + MAX_SECRET_CHARS + "}+");
    /** Élément XML dont le nom est une clé sensible, valeur sur une seule ligne. */
    private static final Pattern XML_SECRET_ELEMENT = Pattern.compile(
            "(?i)(<(password|passwd|secret|token|api[_-]?key|client[_-]?secret|access[_-]?key|private[_-]?key)>)"
                    + "[^<\\r\\n]{1," + MAX_SECRET_CHARS + "}+(</\\2>)");
    /** Jeton placé seul dans la partie utilisateur d'une URL ({@code https://<jeton>@hôte}). */
    private static final Pattern URI_TOKEN_USER = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]{0,31}+://)[A-Za-z0-9_-]{20," + MAX_URI_USER_CHARS + "}+(@)");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\."
                    + "[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\."
                    + "[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\b");
    private static final Pattern URI_CREDENTIAL = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]{0,31}+://[^\\s/:@]{1," + MAX_URI_USER_CHARS + "}+:)"
                    + "([^\\s/@]{3," + MAX_SECRET_CHARS + "}+)(@)");

    /**
     * Valeur d'une forme ci-dessus qui dépasse sa borne : la redaction refuse le contenu entier,
     * comme {@link SecretAssignmentScanner}, plutôt que de laisser passer la valeur en clair.
     */
    private static final Pattern OVERSIZED_VALUE = Pattern.compile(
            "(?i)\\bBearer[ \\t]{1,8}+[A-Za-z0-9._~+/-]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|(?<![\\w-])--?(?:password|passwd|pwd|token|secret|api-key)[ \\t]{1,8}+(?![-\\s])"
                    + "[^\\s\"'`]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|<(?:password|passwd|secret|token|api[_-]?key|client[_-]?secret|access[_-]?key|private[_-]?key)>"
                    + "[^<\\r\\n]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|\\b[a-z][a-z0-9+.-]{0,31}+://[A-Za-z0-9_-]{" + (MAX_URI_USER_CHARS + 1) + ",}+@");

    private SensitiveContentRedactor() {
    }

    public static String redact(String content) {
        Objects.requireNonNull(content, "content");
        if (OVERSIZED_VALUE.matcher(content).find()) {
            throw new OversizedSecretException();
        }
        String redacted = replacePrivateKeyBlocks(content);
        redacted = STRUCTURED_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = PASSWORD_OPTION.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = XML_SECRET_ELEMENT.matcher(redacted).replaceAll("$1" + REDACTED + "$3");
        redacted = SecretAssignmentScanner.redact(redacted);
        redacted = URI_TOKEN_USER.matcher(redacted).replaceAll("$1" + REDACTED + "$2");
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
            if (!beginMarker.endsWith(PRIVATE_KEY_SUFFIX) && !beginMarker.endsWith(PGP_PRIVATE_KEY_SUFFIX)) {
                output.append(content, cursor, markerEnd);
                cursor = markerEnd;
                continue;
            }

            String keyType = beginMarker.substring(PRIVATE_KEY_BEGIN.length(), beginMarker.length() - 5);
            String endMarker = PRIVATE_KEY_END + keyType + "-----";
            int end = content.indexOf(endMarker, markerEnd);
            if (end < 0) {
                output.append(content, cursor, begin).append(REDACTED);
                appendLineSeparators(output, content, begin, content.length());
                cursor = content.length();
                continue;
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

}
