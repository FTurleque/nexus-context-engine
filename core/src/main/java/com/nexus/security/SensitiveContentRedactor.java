package com.nexus.security;

import java.util.List;
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
    public static final String POLICY_VERSION = "secret-redaction-v8";

    private static final String REDACTED = "[REDACTED]";
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN ";
    private static final String PRIVATE_KEY_END = "-----END ";
    private static final String PRIVATE_KEY_SUFFIX = "PRIVATE KEY-----";
    private static final String PGP_PRIVATE_KEY_SUFFIX = "PRIVATE KEY BLOCK-----";
    private static final int MAX_SECRET_CHARS = 4096;
    private static final int MAX_URI_USER_CHARS = 1024;
    /** Classe du corps et des gardes d'un jeton à tirets ({@code sk-}, {@code xox?-}, {@code glpat-}). */
    private static final String DASH_TOKEN_CHAR = "[A-Za-z0-9_-]";

    /** Composants d'un nom d'option ou d'élément XML qui portent une valeur sensible. */
    private static final String SENSITIVE_NAME = "(?:password|passwd|pwd|token|secret|api[_-]?key"
            + "|client[_-]?secret|access[_-]?key|access[_-]?token|private[_-]?key)";
    /** Nom d'option ({@code --password}, {@code --db-password}, {@code -token}). */
    private static final String OPTION_NAME = "(?<![\\w-])--?+(?:[A-Za-z0-9]++-)*+" + SENSITIVE_NAME;
    /** Nom d'élément XML, éventuellement pointé ({@code db.password}), et ses attributs. */
    private static final String XML_OPEN = "<((?:[A-Za-z0-9_-]++[._-])*+" + SENSITIVE_NAME + ")"
            + "(?:[ \\t][^<>\\r\\n]{0,256}+)?+>";

    /**
     * Jetons à préfixe fixe, un motif par famille. {@code sk-}, {@code xox?-}, {@code glpat-} et
     * {@code AIza} incluent {@code -} et ne commencent ni ne finissent au milieu d'un identifiant à
     * tirets ; GitHub, AWS, npm et Stripe ont une classe de caractères sans {@code -} et finissent
     * sur une frontière de mot. Chaque motif borne sa longueur et reste possessif ; le corps d'une
     * famille bornée utilise la classe de sa garde de fin et de {@link #OVERSIZED_TOKENS}.
     *
     * <p>Les motifs s'appliquent l'un après l'autre, familles à tirets d'abord : un jeton à
     * frontière de mot peut figurer après un {@code -} dans le corps d'un jeton à tirets, qui est
     * alors masqué en entier. Aucun motif ne commence dans le corps d'un autre ni juste après lui,
     * donc l'ordre ne change pas le résultat au-delà de ce cas.</p>
     */
    private static final List<Pattern> STRUCTURED_TOKENS = List.of(
            dashToken("sk-(?:proj-|ant-)?+" + DASH_TOKEN_CHAR + "{20,255}+"),
            dashToken("xox[abposr]-" + DASH_TOKEN_CHAR + "{10,255}+"),
            dashToken("AIza" + DASH_TOKEN_CHAR + "{35}"),
            dashToken("glpat-" + DASH_TOKEN_CHAR + "{20,255}+"),
            wordToken("gh[pousr]_\\w{20,255}+"),
            wordToken("github_pat_\\w{20,255}+"),
            wordToken("(?:AKIA|ASIA)[0-9A-Z]{16}"),
            wordToken("npm_[A-Za-z0-9]{36}"),
            wordToken("(?:sk|rk)_(?:live|test)_\\w{16,255}+"));
    /** Schéma {@code Bearer} d'un en-tête HTTP, quelle que soit la clé qui le porte. */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(Bearer[ \\t]++)[A-Za-z0-9._~+/-]{16," + MAX_SECRET_CHARS + "}+=*+");
    /** Option de ligne de commande suivie de sa valeur, citée ou non, séparée par des espaces. */
    private static final Pattern PASSWORD_OPTION = Pattern.compile(
            "(?i)(" + OPTION_NAME + "[ \\t]++)(?:\"[^\"\\r\\n]{1," + MAX_SECRET_CHARS + "}+\""
                    + "|'[^'\\r\\n]{1," + MAX_SECRET_CHARS + "}+'"
                    + "|(?![-\\s])[^\\s\"'`]{1," + MAX_SECRET_CHARS + "}+)");
    /** Élément XML dont le nom est une clé sensible, valeur ou CDATA sur une seule ligne. */
    private static final Pattern XML_SECRET_ELEMENT = Pattern.compile(
            "(?i)(" + XML_OPEN + ")(?:<!\\[CDATA\\[[^\\]\\r\\n]{1," + MAX_SECRET_CHARS + "}+]]>"
                    + "|[^<\\r\\n]{1," + MAX_SECRET_CHARS + "}+)(</\\2>)");
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
            "(?i)\\bBearer[ \\t]++[A-Za-z0-9._~+/-]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|" + OPTION_NAME + "[ \\t]++(?:\"[^\"\\r\\n]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|'[^'\\r\\n]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|(?![-\\s])[^\\s\"'`]{" + (MAX_SECRET_CHARS + 1) + "})"
                    + "|" + XML_OPEN + "(?:<!\\[CDATA\\[)?+[^<\\]\\r\\n]{" + (MAX_SECRET_CHARS + 1) + "}");
    /** Partie utilisateur ou mot de passe d'une URL au-delà de sa borne. */
    private static final Pattern OVERSIZED_URI = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]{0,31}+://(?:[A-Za-z0-9_-]{" + (MAX_URI_USER_CHARS + 1) + ",}+@"
                    + "|[^\\s/:@]{" + (MAX_URI_USER_CHARS + 1) + ",}+:[^\\s/@]++@"
                    + "|[^\\s/:@]{1," + MAX_URI_USER_CHARS + "}+:[^\\s/@]{" + (MAX_SECRET_CHARS + 1) + ",}+@)");
    /**
     * Jeton structuré plus long que sa borne : refusé, jamais tronqué ni publié en clair. Pour
     * chaque famille bornée de {@link #STRUCTURED_TOKENS}, la borne de refus vaut la borne de
     * masquage plus un, sur le même corps, la même classe de caractères et le même ancrage :
     * {@code \w} après {@code gh?_}, {@code github_pat_}, {@code sk_} et {@code rk_} ;
     * {@code [A-Za-z0-9_-]} après {@code sk-}, {@code glpat-} et {@code xox?-}, dont le corps est
     * compté, pour {@code sk-}, après le préfixe {@code proj-} ou {@code ant-} optionnel consommé
     * de façon possessive.
     */
    private static final List<Pattern> OVERSIZED_TOKENS = List.of(
            Pattern.compile("\\b(?:gh[pousr]_|github_pat_|(?:sk|rk)_(?:live|test)_)\\w{256}"),
            Pattern.compile(notAfterDashToken() + "sk-(?:proj-|ant-)?+" + DASH_TOKEN_CHAR + "{256}"),
            Pattern.compile(notAfterDashToken() + "(?:glpat-|xox[abposr]-)" + DASH_TOKEN_CHAR + "{256}"));

    private SensitiveContentRedactor() {
    }

    public static String redact(String content) {
        Objects.requireNonNull(content, "content");
        // Le refus lit le même texte que le masquage : un jeton collé à un bloc de clé privée y a
        // le même voisinage dans les deux contrôles.
        String redacted = replacePrivateKeyBlocks(content);
        // Les motifs d'URL ne tournent que sur un contenu qui contient un schéma.
        boolean uri = redacted.contains("://");
        if (containsMatch(OVERSIZED_TOKENS, redacted) || OVERSIZED_VALUE.matcher(redacted).find()
                || uri && OVERSIZED_URI.matcher(redacted).find()) {
            throw new OversizedSecretException();
        }
        for (Pattern token : STRUCTURED_TOKENS) {
            redacted = token.matcher(redacted).replaceAll(REDACTED);
        }
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = PASSWORD_OPTION.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = XML_SECRET_ELEMENT.matcher(redacted).replaceAll("$1" + REDACTED + "$3");
        redacted = SecretAssignmentScanner.redact(redacted);
        if (!uri) {
            return redacted;
        }
        redacted = URI_TOKEN_USER.matcher(redacted).replaceAll("$1" + REDACTED + "$2");
        return URI_CREDENTIAL.matcher(redacted).replaceAll("$1" + REDACTED + "$3");
    }

    /**
     * Redaction d'un champ court de sortie publique (signature, nom qualifié, identifiant) : un champ
     * que la redaction refuse devient {@code [REDACTED]} en entier, sans faire échouer la réponse.
     */
    public static String redactField(String value) {
        if (value == null) {
            return null;
        }
        try {
            return redact(value);
        } catch (OversizedSecretException exception) {
            return REDACTED;
        }
    }

    /** Jeton à tirets : ni précédé ni suivi d'un caractère de sa classe. */
    private static Pattern dashToken(String token) {
        return Pattern.compile(notAfterDashToken() + token + "(?!" + DASH_TOKEN_CHAR + ")");
    }

    /** Jeton sans tiret : entre deux frontières de mot. */
    private static Pattern wordToken(String token) {
        return Pattern.compile("\\b" + token + "\\b");
    }

    private static String notAfterDashToken() {
        return "(?<!" + DASH_TOKEN_CHAR + ")";
    }

    private static boolean containsMatch(List<Pattern> patterns, String content) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
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
