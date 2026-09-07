package com.nexus.persistence.sqlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Découpe un script SQLite sans confondre les séparateurs de statements avec
 * les points-virgules présents dans les littéraux, identifiants, commentaires
 * ou corps de triggers.
 */
final class SqlScriptSplitter {

    private SqlScriptSplitter() {
    }

    static List<String> split(String script) {
        return new Parser(Objects.requireNonNull(script, "script")).split();
    }

    private enum Quote {
        NONE,
        SINGLE,
        DOUBLE,
        BACKTICK,
        BRACKET
    }

    private static final class Parser {
        private final String script;
        private final List<String> statements = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private final StringBuilder token = new StringBuilder();
        private final List<String> prefixKeywords = new ArrayList<>(3);

        private int index;
        private Quote quote = Quote.NONE;
        private boolean lineComment;
        private boolean blockComment;
        private boolean triggerStatement;
        private boolean triggerBody;
        private boolean triggerBodyEnded;
        private boolean triggerStepStart;
        private int caseDepth;

        private Parser(String script) {
            this.script = script;
        }

        private List<String> split() {
            while (index < script.length()) {
                if (lineComment) {
                    consumeLineComment();
                } else if (blockComment) {
                    consumeBlockComment();
                } else if (quote != Quote.NONE) {
                    consumeQuotedCharacter();
                } else {
                    consumeUnquotedCharacter();
                }
            }

            flushToken();
            validateTerminatedLexicalState();
            if (triggerBody && !triggerBodyEnded) {
                throw new IllegalArgumentException("CREATE TRIGGER incomplet : END final introuvable");
            }
            addCurrentStatement();
            return List.copyOf(statements);
        }

        private void consumeLineComment() {
            char character = script.charAt(index++);
            if (character == '\n') {
                lineComment = false;
                current.append(character);
            }
        }

        private void consumeBlockComment() {
            if (startsWith("*/")) {
                blockComment = false;
                index += 2;
                appendSeparator();
                return;
            }
            index++;
        }

        private void consumeQuotedCharacter() {
            char character = script.charAt(index);
            current.append(character);
            char closing = closingCharacter(quote);
            if (character != closing) {
                index++;
                return;
            }

            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (next == closing) {
                current.append(next);
                index += 2;
                return;
            }

            quote = Quote.NONE;
            index++;
        }

        private void consumeUnquotedCharacter() {
            if (startsWith("--")) {
                flushToken();
                appendSeparator();
                lineComment = true;
                index += 2;
                return;
            }
            if (startsWith("/*")) {
                flushToken();
                appendSeparator();
                blockComment = true;
                index += 2;
                return;
            }

            char character = script.charAt(index);
            Quote openingQuote = openingQuote(character);
            if (openingQuote != Quote.NONE) {
                flushToken();
                quote = openingQuote;
                current.append(character);
                index++;
                return;
            }

            if (isTokenCharacter(character)) {
                token.append(character);
                current.append(character);
                index++;
                return;
            }

            flushToken();
            if (character == ';') {
                consumeSemicolon();
            } else {
                current.append(character);
            }
            index++;
        }

        private void consumeSemicolon() {
            if (triggerBody && !triggerBodyEnded) {
                current.append(';');
                triggerStepStart = true;
                return;
            }
            addCurrentStatement();
            resetStatementState();
        }

        private void flushToken() {
            if (token.length() == 0) {
                return;
            }
            processKeyword(token.toString().toUpperCase(Locale.ROOT));
            token.setLength(0);
        }

        private void processKeyword(String keyword) {
            if (!triggerBody) {
                detectTriggerPrefix(keyword);
                if (triggerStatement && "BEGIN".equals(keyword)) {
                    triggerBody = true;
                    triggerStepStart = true;
                }
                return;
            }

            if ("CASE".equals(keyword)) {
                caseDepth++;
                triggerStepStart = false;
                return;
            }
            if ("END".equals(keyword)) {
                if (caseDepth > 0) {
                    caseDepth--;
                } else if (triggerStepStart) {
                    triggerBodyEnded = true;
                }
                triggerStepStart = false;
                return;
            }
            triggerStepStart = false;
        }

        private void detectTriggerPrefix(String keyword) {
            if (triggerStatement || prefixKeywords.size() >= 3) {
                return;
            }
            prefixKeywords.add(keyword);
            if (prefixKeywords.size() == 2
                    && "CREATE".equals(prefixKeywords.get(0))
                    && "TRIGGER".equals(prefixKeywords.get(1))) {
                triggerStatement = true;
                return;
            }
            if (prefixKeywords.size() == 3
                    && "CREATE".equals(prefixKeywords.get(0))
                    && ("TEMP".equals(prefixKeywords.get(1)) || "TEMPORARY".equals(prefixKeywords.get(1)))
                    && "TRIGGER".equals(prefixKeywords.get(2))) {
                triggerStatement = true;
            }
        }

        private void addCurrentStatement() {
            String statement = current.toString().trim();
            if (!statement.isBlank()) {
                statements.add(statement);
            }
            current.setLength(0);
        }

        private void resetStatementState() {
            prefixKeywords.clear();
            triggerStatement = false;
            triggerBody = false;
            triggerBodyEnded = false;
            triggerStepStart = false;
            caseDepth = 0;
        }

        private void validateTerminatedLexicalState() {
            if (quote != Quote.NONE) {
                throw new IllegalArgumentException("Script SQL invalide : quote non terminée (" + quote + ")");
            }
            if (blockComment) {
                throw new IllegalArgumentException("Script SQL invalide : commentaire /* ... */ non terminé");
            }
        }

        private boolean startsWith(String value) {
            return script.startsWith(value, index);
        }

        private void appendSeparator() {
            if (current.length() > 0 && !Character.isWhitespace(current.charAt(current.length() - 1))) {
                current.append(' ');
            }
        }

        private static boolean isTokenCharacter(char character) {
            return Character.isLetterOrDigit(character) || character == '_';
        }

        private static Quote openingQuote(char character) {
            return switch (character) {
                case '\'' -> Quote.SINGLE;
                case '"' -> Quote.DOUBLE;
                case '`' -> Quote.BACKTICK;
                case '[' -> Quote.BRACKET;
                default -> Quote.NONE;
            };
        }

        private static char closingCharacter(Quote quote) {
            return switch (quote) {
                case SINGLE -> '\'';
                case DOUBLE -> '"';
                case BACKTICK -> '`';
                case BRACKET -> ']';
                case NONE -> throw new IllegalArgumentException("Aucune quote active");
            };
        }
    }
}
