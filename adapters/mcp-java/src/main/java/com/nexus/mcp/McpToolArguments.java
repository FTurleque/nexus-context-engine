package com.nexus.mcp;

import com.nexus.search.CandidateType;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Responsabilité interne de la frontière MCP. */
final class McpToolArguments {
    static final int MAX_REQUESTED_SOURCES = CandidateType.values().length;
    static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(name + " est obligatoire");
        }
        return string.trim();
    }

    static int positiveInteger(
            Map<String, Object> arguments,
            String name,
            int defaultValue,
            int maximum) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = new BigDecimal(value.toString()).toBigIntegerExact().intValueExact();
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " doit être strictement positif");
            }
            if (parsed > maximum) {
                throw new IllegalArgumentException(name + " doit être inférieur ou égal à " + maximum);
            }
            return parsed;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(name + " doit être un entier dans les bornes autorisées", exception);
        }
    }

    static boolean booleanValue(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " doit être un booléen true ou false");
        };
    }

    static Set<CandidateType> requestedSources(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("requestedSources doit être un tableau");
        }
        if (values.size() > MAX_REQUESTED_SOURCES) {
            throw new IllegalArgumentException(
                    "requestedSources doit contenir au plus " + MAX_REQUESTED_SOURCES + " éléments");
        }
        return values.stream()
                .map(item -> {
                    if (!(item instanceof String stringValue)) {
                        throw new IllegalArgumentException(
                                "requestedSources doit contenir uniquement des chaînes");
                    }
                    return stringValue.trim();
                })
                .filter(item -> !item.isBlank())
                .map(item -> {
                    try {
                        return CandidateType.valueOf(item.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Source de contexte inconnue : " + item, exception);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    static Map<String, String> stringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("constraints doit être un objet");
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

}
