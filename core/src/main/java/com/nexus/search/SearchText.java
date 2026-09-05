package com.nexus.search;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class SearchText {

    private static final Pattern TERM_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}_$#.]+");

    private SearchText() {
    }

    static List<String> terms(String query) {
        return Arrays.stream(TERM_SEPARATOR.split(query.toLowerCase(Locale.ROOT)))
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    static double pathScore(String relativePath, String query) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return 0.0d;
        }
        String normalizedPath = relativePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        long matches = terms.stream().filter(normalizedPath::contains).count();
        return clamp((double) matches / terms.size());
    }

    static double similarity(String left, String right) {
        String a = left.toLowerCase(Locale.ROOT);
        String b = right.toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return 1.0d;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        int distance = levenshtein(a, b);
        return clamp(1.0d - ((double) distance / Math.max(a.length(), b.length())));
    }

    static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
