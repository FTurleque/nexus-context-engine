package com.nexus.cli;

import java.util.Locale;

/** Neutralizes terminal control characters while preserving ordinary text, LF and TAB. */
final class TerminalTextSanitizer {

    private static final int LINE_FEED = 0x0A;
    private static final int HORIZONTAL_TAB = 0x09;

    private TerminalTextSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == LINE_FEED || codePoint == HORIZONTAL_TAB) {
                sanitized.appendCodePoint(codePoint);
            } else if (Character.isISOControl(codePoint)) {
                sanitized.append(String.format(Locale.ROOT, "\\u%04X", codePoint));
            } else {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }
}
