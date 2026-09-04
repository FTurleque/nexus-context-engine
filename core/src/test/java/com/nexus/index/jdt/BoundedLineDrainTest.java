package com.nexus.index.jdt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedLineDrainTest {

    @Test
    void truncatesOneHugeLineAndContinuesDrainingFollowingLines() throws IOException {
        int maxLineChars = 128;
        String input = "x".repeat(1_000_000) + "\nnext diagnostic\n";
        List<String> lines = new ArrayList<>();

        BoundedLineDrain.drain(new StringReader(input), maxLineChars, lines::add);

        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().startsWith("x".repeat(maxLineChars)));
        assertTrue(lines.getFirst().contains("ligne tronquée par NEXUS"));
        assertEquals("next diagnostic", lines.get(1));
    }

    @Test
    void emitsFinalLineWithoutTrailingNewline() throws IOException {
        List<String> lines = new ArrayList<>();

        BoundedLineDrain.drain(new StringReader("last diagnostic"), 128, lines::add);

        assertEquals(List.of("last diagnostic"), lines);
    }
}
