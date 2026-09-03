package com.nexus.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusMcpBooleanParsingTest {

    @Test
    void acceptsBooleanValuesAndCanonicalBooleanStrings() {
        assertTrue(NexusMcpTools.booleanValue(Map.of("flag", true), "flag", false));
        assertFalse(NexusMcpTools.booleanValue(Map.of("flag", false), "flag", true));
        assertTrue(NexusMcpTools.booleanValue(Map.of("flag", "true"), "flag", false));
        assertFalse(NexusMcpTools.booleanValue(Map.of("flag", "false"), "flag", true));
    }

    @Test
    void usesDefaultOnlyWhenArgumentIsAbsent() {
        assertTrue(NexusMcpTools.booleanValue(Map.of(), "flag", true));
        assertFalse(NexusMcpTools.booleanValue(Map.of(), "flag", false));
    }

    @Test
    void rejectsNonBooleanTextInsteadOfCoercingItToFalse() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NexusMcpTools.booleanValue(Map.of("flag", "not-a-boolean"), "flag", false));
    }
}
