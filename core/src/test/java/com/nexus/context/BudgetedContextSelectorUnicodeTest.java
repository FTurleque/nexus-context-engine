package com.nexus.context;

import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetedContextSelectorUnicodeTest {

    @Test
    void characterFallbackNeverSplitsSupplementaryUnicodeCodePoints() {
        String content = "😀".repeat(100);
        ContextFragment fragment = new ContextFragment(
                CandidateType.FILE,
                Path.of("emoji.txt"),
                null,
                1,
                1,
                content,
                1.0d,
                Map.of(),
                List.of());

        ContextSelectionResult result = new BudgetedContextSelector(CharSequence::length)
                .select(List.of(fragment), 80, false);

        assertEquals(1, result.items().size());
        String selected = result.items().getFirst().content();
        assertTrue(result.items().getFirst().truncated());
        assertFalse(selected.equals(content));
        assertWellFormedUtf16(selected);
    }

    private static void assertWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < value.length(), "high surrogate must have a following low surrogate");
                assertTrue(Character.isLowSurrogate(value.charAt(++index)),
                        "high surrogate must be followed by a low surrogate");
            } else {
                assertFalse(Character.isLowSurrogate(current), "low surrogate must have a preceding high surrogate");
            }
        }
    }
}
