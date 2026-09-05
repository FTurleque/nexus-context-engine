package com.nexus.index.jdt;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Draine un flux texte ligne par ligne sans autoriser une ligne individuelle à
 * faire croître la mémoire sans limite.
 */
final class BoundedLineDrain {

    private static final int BUFFER_CHARS = 4 * 1024;
    private static final String TRUNCATION_MARKER = "... [ligne tronquée par NEXUS]";

    private BoundedLineDrain() {
    }

    static void drain(Reader reader, int maxLineChars, Consumer<String> consumer) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(consumer, "consumer");
        if (maxLineChars <= 0) {
            throw new IllegalArgumentException("maxLineChars must be greater than zero");
        }

        char[] buffer = new char[BUFFER_CHARS];
        StringBuilder line = new StringBuilder(Math.min(maxLineChars, BUFFER_CHARS));
        boolean truncated = false;
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            for (int index = 0; index < read; index++) {
                truncated = processCharacter(
                        buffer[index], line, maxLineChars, truncated, consumer);
            }
        }
        if (!line.isEmpty() || truncated) {
            emit(consumer, line, truncated);
        }
    }

    private static boolean processCharacter(
            char character,
            StringBuilder line,
            int maxLineChars,
            boolean truncated,
            Consumer<String> consumer) {
        if (character == '\n') {
            emit(consumer, line, truncated);
            line.setLength(0);
            return false;
        }
        if (character == '\r') {
            return truncated;
        }
        if (line.length() < maxLineChars) {
            line.append(character);
            return truncated;
        }
        return true;
    }

    private static void emit(Consumer<String> consumer, StringBuilder line, boolean truncated) {
        consumer.accept(truncated
                ? line + TRUNCATION_MARKER
                : line.toString());
    }
}
