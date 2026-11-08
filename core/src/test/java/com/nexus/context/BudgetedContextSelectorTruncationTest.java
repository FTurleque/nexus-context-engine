package com.nexus.context;

import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetedContextSelectorTruncationTest {

    private static final String TRUNCATION_MARKER = "... [fragment tronqué par NEXUS]";
    private static final Pattern LINE_SEPARATOR = Pattern.compile("\\R");
    private static final String[] ALPHABET = {
        "a", "Z", "0", " ", "\t", "é", "ß", "漢", "字", "😀", "𝄞", "{", "}", ";", "/"
    };
    private static final String[] SEPARATORS = {"\n", "\r\n", "\r", "\u2028"};

    @Test
    void dichotomyMatchesTheLinearReferenceOnUnicodeMultilineFragments() {
        List<TokenEstimator> estimators = List.of(new HeuristicTokenEstimator(), CharSequence::length);
        Random random = new Random(20261017L);
        List<String> contents = new ArrayList<>(List.of(
                "",
                "une seule ligne sans séparateur",
                "\n\n\n",
                "\n\r\n\ncorps après des lignes vides\nsuite 漢字 😀\n\nfin",
                "😀".repeat(200),
                "ligne 1\nligne 2\r\nligne 3\rligne 4\u2028fin\n"));
        for (int sample = 0; sample < 120; sample++) {
            contents.add(randomContent(random));
        }
        int compared = 0;
        for (TokenEstimator estimator : estimators) {
            BudgetedContextSelector selector = new BudgetedContextSelector(estimator);
            for (String content : contents) {
                for (int budget : new int[] {1, 2, 5, 13, 24, 37, 64, 100, 257, 1000, 100_000}) {
                    BudgetedContextSelector.TruncatedContent expected = linearReference(estimator, content, budget);
                    BudgetedContextSelector.TruncatedContent actual = selector.truncate(content, budget);
                    assertEquals(expected, actual, () -> "budget=" + budget + " content=" + content);
                    compared++;
                }
            }
        }
        assertTrue(compared > 2_500);
    }

    @Test
    void largeFragmentUsesALogarithmicNumberOfEstimations() {
        int lineCount = 4_096;
        StringBuilder content = new StringBuilder();
        for (int line = 0; line < lineCount; line++) {
            if (line > 0) {
                content.append('\n');
            }
            content.append("ligne ").append(line).append(" — 漢字 😀");
        }
        AtomicInteger calls = new AtomicInteger();
        HeuristicTokenEstimator heuristic = new HeuristicTokenEstimator();
        TokenEstimator counting = text -> {
            calls.incrementAndGet();
            return heuristic.estimate(text);
        };

        BudgetedContextSelector.TruncatedContent truncated =
                new BudgetedContextSelector(counting).truncate(content.toString(), 500);

        assertTrue(truncated.sourceLines() > 0 && truncated.sourceLines() < lineCount);
        int maximum = (int) Math.ceil(Math.log(lineCount) / Math.log(2)) + 2;
        assertTrue(calls.get() <= maximum, "estimations=" + calls.get() + " maximum=" + maximum);
    }

    private static String randomContent(Random random) {
        int lines = 1 + random.nextInt(40);
        StringBuilder builder = new StringBuilder();
        for (int line = 0; line < lines; line++) {
            if (line > 0) {
                builder.append(SEPARATORS[random.nextInt(SEPARATORS.length)]);
            }
            int length = random.nextInt(60);
            for (int index = 0; index < length; index++) {
                builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
        }
        return builder.toString();
    }

    /** Sélection ligne à ligne de référence : un préfixe reconstruit et estimé par ligne. */
    private static BudgetedContextSelector.TruncatedContent linearReference(
            TokenEstimator estimator, String content, int budget) {
        String[] lines = LINE_SEPARATOR.split(content, -1);
        StringBuilder builder = new StringBuilder();
        int includedLines = 0;
        for (String line : lines) {
            String candidate = builder.isEmpty() ? line : builder + System.lineSeparator() + line;
            String withMarker = candidate + System.lineSeparator() + TRUNCATION_MARKER;
            if (estimator.estimate(withMarker) > budget) {
                break;
            }
            builder.setLength(0);
            builder.append(candidate);
            includedLines++;
        }
        if (includedLines == lines.length) {
            String value = builder.toString();
            return new BudgetedContextSelector.TruncatedContent(value, estimator.estimate(value), includedLines);
        }
        if (includedLines > 0) {
            String value = builder + System.lineSeparator() + TRUNCATION_MARKER;
            return new BudgetedContextSelector.TruncatedContent(value, estimator.estimate(value), includedLines);
        }
        return characterReference(estimator, content, budget);
    }

    private static BudgetedContextSelector.TruncatedContent characterReference(
            TokenEstimator estimator, String content, int budget) {
        int low = 0;
        int high = content.codePointCount(0, content.length());
        String best = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String candidate = content.substring(0, content.offsetByCodePoints(0, middle))
                    + System.lineSeparator() + TRUNCATION_MARKER;
            if (estimator.estimate(candidate) <= budget) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best.isBlank()) {
            return new BudgetedContextSelector.TruncatedContent("", 0, 0);
        }
        int sourceLines = Math.max(1, LINE_SEPARATOR.split(best, -1).length - 1);
        return new BudgetedContextSelector.TruncatedContent(best, estimator.estimate(best), sourceLines);
    }
}
