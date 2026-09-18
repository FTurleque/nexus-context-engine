package com.nexus.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scan linéaire des affectations sensibles, limité à une ligne et 4096 caractères par valeur. */
final class SecretAssignmentScanner {
    private static final int MAX_SECRET_CHARS = 4096;
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern SENSITIVE_KEY_COMPONENT = Pattern.compile(
            "(?i)(?:^|[_.-])(?:api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|"
                    + "secret[_-]?access[_-]?key|password|passwd|secret)(?=$|[_.-])");

    private SecretAssignmentScanner() { }

    static String redact(String content) {
        StringBuilder output = new StringBuilder(content.length());
        int cursor = 0;
        int start;
        while ((start = assignmentValueStart(content, cursor)) >= 0) {
            output.append(content, cursor, start);
            char quote = start < content.length() ? content.charAt(start) : 0;
            if (quote == '\'' || quote == '"') {
                int end = quotedEnd(content, start, quote);
                output.append(quote).append(REDACTED);
                if (end < content.length() && content.charAt(end) == quote) {
                    output.append(quote);
                    end++;
                }
                cursor = end;
            } else {
                int end = scalarEnd(content, start);
                int trimmed = end;
                while (trimmed > start && horizontal(content.charAt(trimmed - 1))) trimmed--;
                output.append(REDACTED).append(content, trimmed, end);
                cursor = end;
            }
        }
        return output.append(content, cursor, content.length()).toString();
    }

    /** Les noms composites sont parcourus sans répétition regex récursive ni copie de sous-chaîne. */
    private static int assignmentValueStart(String text, int cursor) {
        Matcher sensitive = SENSITIVE_KEY_COMPONENT.matcher(text);
        while (cursor < text.length()) {
            char first = text.charAt(cursor);
            boolean quoted = first == '\'' || first == '"';
            if ((!quoted && !keyCharacter(first))
                    || (cursor > 0 && identifierCharacter(text.charAt(cursor - 1)))) {
                cursor++;
                continue;
            }
            int begin = quoted ? cursor + 1 : cursor;
            int end = begin;
            while (end < text.length() && keyCharacter(text.charAt(end))) end++;
            int afterKey = end;
            if (quoted) {
                if (end >= text.length() || text.charAt(end) != first) {
                    cursor = Math.max(cursor + 1, end);
                    continue;
                }
                afterKey++;
            }
            if (end > begin && sensitive.region(begin, end).find()) {
                int separator = skipHorizontal(text, afterKey);
                if (separator < text.length() && (text.charAt(separator) == ':' || text.charAt(separator) == '=')) {
                    return skipHorizontal(text, separator + 1);
                }
            }
            cursor = Math.max(cursor + 1, afterKey);
        }
        return -1;
    }

    private static int skipHorizontal(String text, int start) {
        int end = start;
        while (end < text.length() && end - start < 32 && horizontal(text.charAt(end))) end++;
        return end;
    }

    private static boolean keyCharacter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                || c == '_' || c == '.' || c == '-';
    }

    private static boolean identifierCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static int quotedEnd(String text, int start, char quote) {
        boolean escaped = false;
        int end = start + 1;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (newline(c)) return end;
            if (!escaped && c == quote) {
                // YAML représente également une apostrophe par deux apostrophes.
                if (quote != '\'' || end + 1 == text.length() || text.charAt(end + 1) != '\'') return end;
                requireWithinLimit(end + 1 - start);
                end += 2;
                continue;
            }
            requireWithinLimit(end - start);
            escaped = !escaped && c == '\\';
            end++;
        }
        return end;
    }

    private static int scalarEnd(String text, int start) {
        int end = text.startsWith(REDACTED, start) ? start + REDACTED.length() : start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (newline(c) || c == ',' || c == '}' || c == ']' || c == ';'
                    || (c == '#' && (end == start || horizontal(text.charAt(end - 1))))) break;
            requireWithinLimit(end - start + 1);
            end++;
        }
        return end;
    }

    private static boolean horizontal(char c) { return c == ' ' || c == '\t'; }

    private static boolean newline(char c) { return c == '\r' || c == '\n'; }

    private static void requireWithinLimit(int length) {
        if (length > MAX_SECRET_CHARS) {
            // Refuser le contenu entier évite de publier le suffixe d'un secret trop long.
            throw new IllegalArgumentException("Valeur sensible dépassant la limite de redaction");
        }
    }
}
