package com.nexus.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Responsabilité interne de la frontière MCP. */
final class McpToolSchemas {
    static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> integerProperty(String description, int maximum) {
        return Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", maximum,
                "description", description);
    }

    static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    static Map<String, Object> arrayOfStringsProperty(String description, int maxItems) {
        return Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "maxItems", maxItems,
                "description", description);
    }

    static Map<String, Object> objectProperty(String description) {
        return Map.of(
                "type", "object",
                "additionalProperties", Map.of("type", "string"),
                "description", description);
    }

}
