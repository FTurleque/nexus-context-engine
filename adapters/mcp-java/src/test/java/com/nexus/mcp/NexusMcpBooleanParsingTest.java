package com.nexus.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusMcpBooleanParsingTest {

    @Test
    void acceptsBooleanValuesAndCanonicalBooleanStrings() {
        assertTrue(McpToolArguments.booleanValue(Map.of("flag", true), "flag", false));
        assertFalse(McpToolArguments.booleanValue(Map.of("flag", false), "flag", true));
        assertTrue(McpToolArguments.booleanValue(Map.of("flag", "true"), "flag", false));
        assertFalse(McpToolArguments.booleanValue(Map.of("flag", "false"), "flag", true));
    }

    @Test
    void usesDefaultOnlyWhenArgumentIsAbsent() {
        assertTrue(McpToolArguments.booleanValue(Map.of(), "flag", true));
        assertFalse(McpToolArguments.booleanValue(Map.of(), "flag", false));
    }

    @Test
    void rejectsNonBooleanTextInsteadOfCoercingItToFalse() {
        Map<String, Object> invalidArguments = Map.of("flag", "not-a-boolean");
        assertThrows(
                IllegalArgumentException.class,
                () -> McpToolArguments.booleanValue(invalidArguments, "flag", false));
    }
}
