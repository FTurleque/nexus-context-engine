package com.nexus.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSymbolRangeValidationTest {

    @Test
    void rejectsZeroStartLine() {
        assertThrows(IllegalArgumentException.class, () -> symbol(0, 1));
    }

    @Test
    void rejectsNegativeStartLine() {
        assertThrows(IllegalArgumentException.class, () -> symbol(-1, 1));
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class, () -> symbol(3, 2));
    }

    @Test
    void acceptsSingleLineAndLastLineRanges() {
        assertDoesNotThrow(() -> symbol(1, 1));
        assertTrue(CodeSymbol.isWithinLineCount(5, 5, 5));
        assertFalse(CodeSymbol.isWithinLineCount(5, 6, 5));
        assertFalse(CodeSymbol.isWithinLineCount(1, 1, 0));
    }

    private static CodeSymbol symbol(int startLine, int endLine) {
        return new CodeSymbol(
                SymbolKind.METHOD,
                "run",
                "demo.Type#run()",
                "run()",
                startLine,
                endLine);
    }
}
