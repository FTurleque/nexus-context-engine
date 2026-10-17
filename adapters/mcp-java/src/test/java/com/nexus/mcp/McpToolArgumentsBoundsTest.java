package com.nexus.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolArgumentsBoundsTest {

    @Test
    void acceptsBoundedStringConstraints() {
        assertEquals(
                Map.of("language", "java", "scope", "tests"),
                McpToolArguments.stringMap(Map.of("language", "java", "scope", "tests")));
    }

    @Test
    void rejectsTooManyConstraints() {
        Map<String, Object> constraints = new LinkedHashMap<>();
        for (int index = 0; index <= McpToolArguments.MAX_CONSTRAINTS; index++) {
            constraints.put("key-" + index, "value");
        }
        assertThrows(IllegalArgumentException.class, () -> McpToolArguments.stringMap(constraints));
    }

    @Test
    void rejectsNonStringConstraintValuesBeforeCallingToString() {
        Object hostileValue = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("toString must not be called for unsupported constraint values");
            }
        };
        assertThrows(
                IllegalArgumentException.class,
                () -> McpToolArguments.stringMap(Map.of("key", hostileValue)));
    }

    @Test
    void rejectsOversizedConstraintValue() {
        String oversized = "x".repeat(McpToolArguments.MAX_CONSTRAINT_VALUE_CHARS + 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpToolArguments.stringMap(Map.of("key", oversized)));
    }
}
