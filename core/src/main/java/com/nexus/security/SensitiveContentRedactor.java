package com.nexus.security;

import java.util.List;
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

    /** Version persistée dans les index dérivés ; toute évolution impose leur reconstruction. */
    public static final String POLICY_VERSION = "secret-redaction-v10";

    private static final String REDACTED = "[REDACTED]";
    /** Tirets d'ouverture et de fin d'un marqueur de clé privée. */
    private static final String MARKER_DASHES = "-----";
    /** Longueur de {@code -----BEGIN } : tirets, mot {@code BEGIN} et un blanc. */
    private static final int PRIVATE_KEY_BEGIN_LENGTH = 11;
    private static final String PRIVATE_KEY_SUFFIX = "PRIVATE KEY";
    private static final String PGP_PRIVATE_KEY_SUFFIX = "PRIVATE KEY BLOCK";
    /** Espace insécable (U+00A0), blanc admis dans un marqueur de clé privée. */
    private static final char NO_BREAK_SPACE = (char) 0x00A0;
    private static final int MAX_SECRET_CHARS = 4096;
    private static final int MAX_URI_USER_CHARS = 1024;
    /** Classe du corps et des gardes d'un jeton à tirets ({@code sk-}, {@code xox?-}, {@code glpat-}). */
    private static final String DASH_TOKEN_CHAR = "[A-Za-z0-9_-]";

    /**
     * Composants d'un nom d'option ou d'élément XML qui portent une valeur sensible. Ce nom et ceux
     * qui suivent ne servent que dans des motifs {@code (?i)} : leurs classes s'écrivent en minuscules.
     */
    private static final String SENSITIVE_NAME = "(?:password|passwd|pwd|token|secret|api[_-]?key"
            + "|client[_-]?secret|access[_-]?key|access[_-]?token|private[_-]?key)";
    /**
     * Nom d'option ({@code --password}, {@code --db-password}, {@code --api-key}, {@code -token}) : le
     * nom est consommé en entier sans retour arrière, puis un lookbehind de longueur bornée vérifie
     * qu'il se termine par une clé sensible précédée d'un {@code -}.
     */
    private static final String OPTION_NAME = "(?<![\\w-])--?+[a-z0-9_-]++(?<=-" + SENSITIVE_NAME + ")";
    /** Début d'une valeur d'option non citée : ni tiret (option suivante), ni blanc, ni quote. */
    private static final String UNQUOTED_OPTION_VALUE_START = "(?![-\\s\"'`])";
    /**
     * Nom d'élément XML qui se termine par une clé sensible placée en début de nom ou après
     * {@code .}, {@code -} ou {@code _} ({@code db.password}, {@code db-password}, {@code api_key}),
     * puis ses attributs. Même construction que {@link #OPTION_NAME} : nom possessif, lookbehind borné.
     */
    private static final String XML_OPEN = "<([a-z0-9._-]++(?<=[<._-]" + SENSITIVE_NAME + "))"
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
    /**
     * Préfixes littéraux de chaque motif de {@link #STRUCTURED_TOKENS}, dans le même ordre : un motif
     * ne peut réussir que si le contenu contient l'un d'eux, donc sa passe est sautée sinon, sans
     * changer le résultat. {@code [REDACTED]} ne contient aucun de ces préfixes.
     */
    private static final List<List<String>> STRUCTURED_TOKEN_PREFIXES = List.of(
            List.of("sk-"), List.of("xox"), List.of("AIza"), List.of("glpat-"),
            List.of("ghp_", "gho_", "ghu_", "ghs_", "ghr_"),
            List.of("github_pat_"), List.of("AKIA", "ASIA"), List.of("npm_"), List.of("sk_", "rk_"));
    /** Schéma {@code Bearer} d'un en-tête HTTP, quelle que soit la clé qui le porte. */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(Bearer[ \\t]++)[A-Za-z0-9._~+/-]{16," + MAX_SECRET_CHARS + "}+=*+");
    /**
     * Option de ligne de commande suivie de sa valeur, séparée par des espaces : citée, ou non citée
     * jusqu'au prochain blanc, quotes intérieures comprises, pour qu'aucun suffixe ne reste en clair.
     */
    private static final Pattern PASSWORD_OPTION = Pattern.compile(
            "(?i)(" + OPTION_NAME + "[ \\t]++)(?:\"[^\"\\r\\n]{1," + MAX_SECRET_CHARS + "}+\""
                    + "|'[^'\\r\\n]{1," + MAX_SECRET_CHARS + "}+'"
                    + "|" + UNQUOTED_OPTION_VALUE_START + "\\S{1," + MAX_SECRET_CHARS + "}+)");
    /** Ouverture d'un élément XML sensible ; le groupe 1 est le nom. */
    private static final Pattern XML_SECRET_OPEN = Pattern.compile("(?i)" + XML_OPEN);
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";
    /**
     * Élément XML dont le nom est une clé sensible, valeur ou CDATA sur une seule ligne. Appliqué
     * par {@link #redactXmlSecretElements(String)}, qui évalue l'alternative CDATA sans regex.
     */
    private static final Pattern XML_SECRET_ELEMENT = Pattern.compile(
            "(?i)(" + XML_OPEN + ")(?:<!\\[CDATA\\[[^\\]\\r\\n]{1," + MAX_SECRET_CHARS + "}+]]>"
                    + "|[^<\\r\\n]{1," + MAX_SECRET_CHARS + "}+)(</\\2>)");
    /** Jeton placé seul dans la partie utilisateur d'une URL ({@code https://<jeton>@hôte}). */
    private static final Pattern URI_TOKEN_USER = Pattern.compile(
            "(?i)(\\b[a-z][a-z0-9+.-]{0,31}+://)[A-Za-z0-9_-]{20," + MAX_URI_USER_CHARS + "}+(@)");
    /** JWT ; appliqué par {@link #redactJwts(String)}, qui ne retente pas une même suite base64url. */
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\."
                    + "[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\."
                    + "[A-Za-z0-9_-]{10," + MAX_SECRET_CHARS + "}+\\b");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("\\b");
    private static final String JWT_PREFIX = "eyJ";
    private static final int MIN_JWT_SEGMENT_CHARS = 10;
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
                    + "|" + UNQUOTED_OPTION_VALUE_START + "\\S{" + (MAX_SECRET_CHARS + 1) + "})"
                    + "|" + XML_OPEN + "(?:<!\\[CDATA\\[[^<\\]\\r\\n]{" + (MAX_SECRET_CHARS + 1) + "}"
                    + "|[^<\\r\\n]{" + (MAX_SECRET_CHARS + 1) + "})");
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
        for (int index = 0; index < STRUCTURED_TOKENS.size(); index++) {
            if (containsAny(redacted, STRUCTURED_TOKEN_PREFIXES.get(index))) {
                redacted = STRUCTURED_TOKENS.get(index).matcher(redacted).replaceAll(REDACTED);
            }
        }
        redacted = redactJwts(redacted);
        redacted = BEARER.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = PASSWORD_OPTION.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = redactXmlSecretElements(redacted);
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

    /**
     * Applique {@link #JWT} comme {@code replaceAll}, sans retenter les débuts qui ne peuvent pas
     * réussir. Tous les {@code eyJ} d'une même suite de caractères base64url partagent la fin de leur
     * premier segment : si elle ne mène pas à un {@code .} dans la borne, ou si les segments suivants
     * échouent, aucun autre début de la suite ne réussit, sauf, pour une suite trop longue, un début
     * situé à moins de {@link #MAX_SECRET_CHARS} caractères de sa fin. Chaque caractère est donc
     * balayé un nombre borné de fois, et le résultat est celui du motif seul.
     */
    private static String redactJwts(String content) {
        int candidate = content.indexOf(JWT_PREFIX);
        if (candidate < 0) {
            return content;
        }
        Matcher jwt = JWT.matcher(content).useTransparentBounds(true).useAnchoringBounds(false);
        Matcher boundary = WORD_BOUNDARY.matcher(content).useTransparentBounds(true).useAnchoringBounds(false);
        StringBuilder output = new StringBuilder(content.length());
        int copied = 0;
        int runEnd = -1;
        while (candidate >= 0) {
            if (candidate >= runEnd) {
                runEnd = base64UrlRunEnd(content, candidate + JWT_PREFIX.length());
            }
            int next;
            if (!boundary.region(candidate, content.length()).lookingAt()) {
                // Un début collé à un caractère de mot échoue seul : le suivant peut suivre un tiret.
                next = candidate + 1;
            } else {
                next = jwtSkipTarget(content, candidate, runEnd);
                if (next < 0 && jwt.region(candidate, content.length()).lookingAt()) {
                    output.append(content, copied, candidate).append(REDACTED);
                    copied = jwt.end();
                    next = copied;
                } else if (next < 0) {
                    next = runEnd;
                }
            }
            candidate = content.indexOf(JWT_PREFIX, next);
        }
        return copied == 0 ? content : output.append(content, copied, content.length()).toString();
    }

    /**
     * Position de reprise quand le premier segment d'un début de JWT ne peut pas réussir, ou
     * {@code -1} quand le motif doit être tenté : premier segment de 10 à {@link #MAX_SECRET_CHARS}
     * caractères suivi d'un {@code .}.
     */
    private static int jwtSkipTarget(String content, int candidate, int runEnd) {
        int firstSegment = runEnd - candidate - JWT_PREFIX.length();
        boolean dot = runEnd < content.length() && content.charAt(runEnd) == '.';
        if (!dot || firstSegment < MIN_JWT_SEGMENT_CHARS) {
            return runEnd;
        }
        if (firstSegment > MAX_SECRET_CHARS) {
            return Math.max(candidate + 1, runEnd - MAX_SECRET_CHARS - JWT_PREFIX.length());
        }
        return -1;
    }

    private static int base64UrlRunEnd(String content, int from) {
        int index = from;
        while (index < content.length() && isBase64Url(content.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isBase64Url(char character) {
        boolean letter = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
        boolean digit = character >= '0' && character <= '9';
        return letter || digit || character == '_' || character == '-';
    }

    /**
     * Applique {@link #XML_SECRET_ELEMENT} comme {@code replaceAll}. Après une ouverture suivie de
     * {@code <![CDATA[}, l'alternative de valeur simple échoue sur le {@code <} : seule l'alternative
     * CDATA peut réussir, et elle est évaluée sans regex. La fin du corps CDATA (premier {@code ]}, CR
     * ou LF) est mémorisée par un pointeur qui ne recule jamais : les ouvertures comprises dans un
     * même corps la partagent sans le rebalayer. Les autres ouvertures passent par le motif.
     */
    private static String redactXmlSecretElements(String content) {
        Matcher open = XML_SECRET_OPEN.matcher(content);
        if (!open.find()) {
            return content;
        }
        Matcher element = XML_SECRET_ELEMENT.matcher(content).useTransparentBounds(true).useAnchoringBounds(false);
        StringBuilder output = new StringBuilder(content.length());
        int copied = 0;
        int cdataBodyEnd = -1;
        int from;
        do {
            int start = open.start();
            int openEnd = open.end();
            int matchEnd;
            int closeStart;
            if (asciiRegionMatchesIgnoreCase(content, openEnd, CDATA_OPEN)) {
                int bodyStart = openEnd + CDATA_OPEN.length();
                if (cdataBodyEnd < bodyStart) {
                    cdataBodyEnd = cdataBodyEnd(content, bodyStart);
                }
                closeStart = cdataBodyEnd + CDATA_CLOSE.length();
                matchEnd = cdataElementEnd(content, bodyStart, cdataBodyEnd, open.group(1));
            } else if (element.region(start, content.length()).lookingAt()) {
                closeStart = element.start(3);
                matchEnd = element.end();
            } else {
                closeStart = -1;
                matchEnd = -1;
            }
            if (matchEnd < 0) {
                from = start + 1;
            } else {
                output.append(content, copied, openEnd).append(REDACTED).append(content, closeStart, matchEnd);
                copied = matchEnd;
                from = matchEnd;
            }
        } while (from < content.length() && open.find(from));
        return copied == 0 ? content : output.append(content, copied, content.length()).toString();
    }

    /**
     * Fin de l'élément pour l'alternative CDATA de {@link #XML_SECRET_ELEMENT}, ou {@code -1} : corps
     * de 1 à {@link #MAX_SECRET_CHARS} caractères terminé par {@code ]]>}, puis fermeture du même nom,
     * casse ignorée comme la référence {@code </\2>} du motif.
     */
    private static int cdataElementEnd(String content, int bodyStart, int bodyEnd, String name) {
        int closeStart = bodyEnd + CDATA_CLOSE.length();
        int nameEnd = closeStart + 2 + name.length();
        boolean body = bodyEnd > bodyStart && bodyEnd - bodyStart <= MAX_SECRET_CHARS
                && content.startsWith(CDATA_CLOSE, bodyEnd);
        boolean closing = content.startsWith("</", closeStart)
                && asciiRegionMatchesIgnoreCase(content, closeStart + 2, name)
                && content.startsWith(">", nameEnd);
        return body && closing ? nameEnd + 1 : -1;
    }

    /**
     * Compare {@code expected} au contenu en {@code offset}, casse ignorée pour les seules lettres
     * ASCII, comme {@code (?i)} sans {@code UNICODE_CASE} : {@code ſ} ou le signe Kelvin ne valent
     * ni {@code s} ni {@code k}.
     */
    private static boolean asciiRegionMatchesIgnoreCase(String content, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > content.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (asciiLower(content.charAt(offset + index)) != asciiLower(expected.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static char asciiLower(char character) {
        return character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character;
    }

    private static int cdataBodyEnd(String content, int from) {
        int index = from;
        while (index < content.length()) {
            char character = content.charAt(index);
            if (character == ']' || character == '\r' || character == '\n') {
                break;
            }
            index++;
        }
        return index;
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

    private static boolean containsAny(String content, List<String> literals) {
        for (String literal : literals) {
            if (content.contains(literal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMatch(List<Pattern> patterns, String content) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remplace chaque bloc de clé privée par {@code [REDACTED]} en conservant ses CR et LF.
     *
     * <p>Les tirets de fin d'un marqueur {@code -----BEGIN } sont cherchés sur sa ligne seulement : un
     * marqueur dont la fin n'est pas avant le premier CR ou LF n'ouvre pas de clé. Quand un marqueur
     * n'ouvre pas de clé, la recherche reprend juste après son {@code -----BEGIN } : les tirets de fin
     * d'un marqueur d'un autre type peuvent ouvrir le marqueur suivant
     * ({@code -----BEGIN CERTIFICATE-----BEGIN RSA PRIVATE KEY-----}), et aucun {@code -----BEGIN }
     * situé après ce point n'est sauté. Entre ce point et les tirets trouvés il n'y a aucun
     * {@code -----} : chaque zone est balayée un nombre borné de fois et le coût reste linéaire.</p>
     *
     * <p>Les mots {@code BEGIN}, {@code END} et le type se comparent sans tenir compte de la casse ASCII,
     * et toute suite d'espaces, de tabulations ou d'espaces insécables (U+00A0) vaut un seul blanc :
     * après {@code BEGIN} ou {@code END}, et entre les mots du type. Le marqueur {@code -----END} porte
     * le même type, à la casse et aux blancs près. S'il commence sa ligne, précédé au plus de blancs ou
     * de tirets, le bloc s'arrête après ses tirets ; sinon il s'arrête à la fin de la ligne du marqueur.
     * Sans marqueur {@code -----END}, le bloc court jusqu'à la fin du contenu.</p>
     */
    private static String replacePrivateKeyBlocks(String content) {
        StringBuilder output = new StringBuilder(content.length());
        int cursor = 0;
        while (cursor < content.length()) {
            int begin = nextMarker(content, cursor, "BEGIN");
            if (begin < 0) {
                output.append(content, cursor, content.length());
                break;
            }

            int afterBegin = begin + PRIVATE_KEY_BEGIN_LENGTH;
            int closingDashes = closingDashesOnMarkerLine(content, afterBegin);
            String type = closingDashes < 0 ? "" : normalizedType(content, afterBegin, closingDashes);
            if (!isPrivateKeyType(type)) {
                output.append(content, cursor, afterBegin);
                cursor = afterBegin;
                continue;
            }

            int end = endOfMatchingEndMarker(content, closingDashes + MARKER_DASHES.length(), type);
            if (end < 0) {
                output.append(content, cursor, begin).append(REDACTED);
                appendLineSeparators(output, content, begin, content.length());
                cursor = content.length();
                continue;
            }

            output.append(content, cursor, begin).append(REDACTED);
            appendLineSeparators(output, content, begin, end);
            cursor = end;
        }
        return output.toString();
    }

    /**
     * Position du prochain {@code -----<mot><blanc>} à partir de {@code from}, ou {@code -1}. Chaque
     * occurrence de {@code -----} est examinée en temps constant, puis la recherche avance d'un caractère.
     */
    private static int nextMarker(String content, int from, String word) {
        int position = from;
        while (true) {
            int dashes = content.indexOf(MARKER_DASHES, position);
            if (dashes < 0) {
                return -1;
            }
            int wordStart = dashes + MARKER_DASHES.length();
            if (regionEqualsIgnoreAsciiCase(content, wordStart, word)
                    && wordStart + word.length() < content.length()
                    && isMarkerBlank(content.charAt(wordStart + word.length()))) {
                return dashes;
            }
            position = dashes + 1;
        }
    }

    /**
     * Type {@code content[from, to)} normalisé : blancs de tête retirés, chaque suite de blancs réduite
     * à une espace, lettres ASCII en majuscules. Un blanc final est conservé.
     */
    private static String normalizedType(String content, int from, int to) {
        StringBuilder type = new StringBuilder(to - from);
        boolean pendingBlank = false;
        for (int index = from; index < to; index++) {
            char character = content.charAt(index);
            if (isMarkerBlank(character)) {
                pendingBlank = !type.isEmpty();
                continue;
            }
            if (pendingBlank) {
                type.append(' ');
                pendingBlank = false;
            }
            type.append(asciiUpper(character));
        }
        if (pendingBlank) {
            type.append(' ');
        }
        return type.toString();
    }

    /** Le type normalisé se termine par {@code PRIVATE KEY} ou {@code PRIVATE KEY BLOCK}. */
    private static boolean isPrivateKeyType(String normalizedType) {
        return normalizedType.endsWith(PRIVATE_KEY_SUFFIX) || normalizedType.endsWith(PGP_PRIVATE_KEY_SUFFIX);
    }

    /**
     * Fin du bloc fermé par le premier {@code -----END <type>-----} qui suit {@code from} et porte le
     * type normalisé {@code type}, ou {@code -1}. Un marqueur qui commence sa ligne (seuls des blancs ou
     * des tirets le précèdent) ferme le bloc après ses tirets ; un marqueur au milieu d'une ligne le
     * ferme à la fin de cette ligne (premier CR ou LF, exclu), pour qu'aucun texte collé au marqueur ne
     * sorte en clair. La partie comparée d'une candidate ne contient aucun {@code -----} et chaque
     * caractère du type normalisé consomme au moins un caractère de la candidate : le balayage reste
     * linéaire.
     */
    private static int endOfMatchingEndMarker(String content, int from, String type) {
        int position = from;
        while (true) {
            int candidate = nextMarker(content, position, "END");
            if (candidate < 0) {
                return -1;
            }
            int typeEnd = endOfNormalizedType(content, candidate + MARKER_DASHES.length() + "END".length(), type);
            if (typeEnd >= 0 && content.startsWith(MARKER_DASHES, typeEnd)) {
                int markerEnd = typeEnd + MARKER_DASHES.length();
                return startsItsLine(content, candidate) ? markerEnd : endOfLine(content, markerEnd);
            }
            position = candidate + 1;
        }
    }

    /** Position du premier CR ou LF à partir de {@code from}, ou la longueur du contenu. */
    private static int endOfLine(String content, int from) {
        int index = from;
        while (index < content.length() && content.charAt(index) != '\r' && content.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    /** Seuls des blancs ou des tirets précèdent {@code position} sur sa ligne. */
    private static boolean startsItsLine(String content, int position) {
        for (int index = position - 1; index >= 0; index--) {
            char character = content.charAt(index);
            if (character == '\r' || character == '\n') {
                return true;
            }
            if (character != '-' && !isMarkerBlank(character)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fin du type lu à partir de {@code from} s'il vaut le type normalisé {@code type} (blancs de tête
     * ignorés, suite de blancs pour une espace, casse ASCII ignorée), ou {@code -1}.
     */
    private static int endOfNormalizedType(String content, int from, String type) {
        int index = skipMarkerBlanks(content, from);
        for (int position = 0; position < type.length(); position++) {
            char expected = type.charAt(position);
            if (expected == ' ') {
                if (index >= content.length() || !isMarkerBlank(content.charAt(index))) {
                    return -1;
                }
                index = skipMarkerBlanks(content, index);
            } else {
                if (index >= content.length() || asciiUpper(content.charAt(index)) != expected) {
                    return -1;
                }
                index++;
            }
        }
        return index;
    }

    private static int skipMarkerBlanks(String content, int from) {
        int index = from;
        while (index < content.length() && isMarkerBlank(content.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean regionEqualsIgnoreAsciiCase(String content, int from, String word) {
        if (from + word.length() > content.length()) {
            return false;
        }
        for (int index = 0; index < word.length(); index++) {
            if (asciiUpper(content.charAt(from + index)) != asciiUpper(word.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMarkerBlank(char character) {
        return character == ' ' || character == '\t' || character == NO_BREAK_SPACE;
    }

    private static char asciiUpper(char character) {
        return character >= 'a' && character <= 'z' ? (char) (character - ('a' - 'A')) : character;
    }

    /**
     * Position des premiers {@code -----} à partir de {@code from}, ou {@code -1} si un CR, un LF ou
     * la fin du contenu vient avant. Un seul balayage, qui s'arrête au premier des deux.
     */
    private static int closingDashesOnMarkerLine(String content, int from) {
        for (int index = from; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '\r' || character == '\n') {
                return -1;
            }
            if (character == '-' && content.startsWith(MARKER_DASHES, index)) {
                return index;
            }
        }
        return -1;
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
