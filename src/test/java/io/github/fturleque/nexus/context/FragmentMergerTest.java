package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentMergerTest {

    @Test
    void mergesOverlappingRangesWithoutDuplicatingLinesAndKeepsStrongestScore() {
        ContextFragment first = new ContextFragment(
                CandidateType.SYMBOL,
                Path.of("src/Demo.java"),
                "Demo",
                1,
                3,
                "line1\nline2\nline3",
                0.4d,
                Map.of("lexicalScore", 0.4d),
                List.of("lexical"));
        ContextFragment second = new ContextFragment(
                CandidateType.SYMBOL,
                Path.of("src/Demo.java"),
                "run()",
                3,
                5,
                "line3\nline4\nline5",
                0.1d,
                Map.of("graphScore", 0.1d),
                List.of("graph"));

        List<ContextFragment> merged = new FragmentMerger().merge(List.of(first, second));

        assertEquals(1, merged.size());
        assertEquals(1, merged.getFirst().startLine());
        assertEquals(5, merged.getFirst().endLine());
        assertEquals("line1\nline2\nline3\nline4\nline5", merged.getFirst().content().replace("\r\n", "\n"));
        assertEquals(0.4d, merged.getFirst().score());
        assertTrue(merged.getFirst().scoreComponents().containsKey("lexicalScore"));
        assertFalse(merged.getFirst().scoreComponents().containsKey("graphScore"));
        assertEquals(List.of("lexical", "graph"), merged.getFirst().reasons());
    }
}
