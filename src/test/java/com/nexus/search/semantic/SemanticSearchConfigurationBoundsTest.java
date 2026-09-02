package com.nexus.search.semantic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticSearchConfigurationBoundsTest {

    @Test
    void acceptsOllamaConfigurationAtHardBoundaries() {
        assertEquals(
                SemanticSearchConfiguration.MAX_OLLAMA_DIMENSIONS,
                SemanticSearchConfiguration.boundedPositiveInt(
                        SemanticSearchConfiguration.OLLAMA_DIMENSIONS_ENV,
                        Integer.toString(SemanticSearchConfiguration.MAX_OLLAMA_DIMENSIONS),
                        SemanticSearchConfiguration.MAX_OLLAMA_DIMENSIONS));
        assertEquals(
                SemanticSearchConfiguration.MAX_OLLAMA_TIMEOUT_SECONDS,
                SemanticSearchConfiguration.boundedPositiveLong(
                        SemanticSearchConfiguration.OLLAMA_TIMEOUT_SECONDS_ENV,
                        Long.toString(SemanticSearchConfiguration.MAX_OLLAMA_TIMEOUT_SECONDS),
                        SemanticSearchConfiguration.MAX_OLLAMA_TIMEOUT_SECONDS));
    }

    @Test
    void rejectsOllamaConfigurationAboveHardBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> SemanticSearchConfiguration.boundedPositiveInt(
                SemanticSearchConfiguration.OLLAMA_DIMENSIONS_ENV,
                Integer.toString(SemanticSearchConfiguration.MAX_OLLAMA_DIMENSIONS + 1),
                SemanticSearchConfiguration.MAX_OLLAMA_DIMENSIONS));
        assertThrows(IllegalArgumentException.class, () -> SemanticSearchConfiguration.boundedPositiveLong(
                SemanticSearchConfiguration.OLLAMA_TIMEOUT_SECONDS_ENV,
                Long.toString(SemanticSearchConfiguration.MAX_OLLAMA_TIMEOUT_SECONDS + 1L),
                SemanticSearchConfiguration.MAX_OLLAMA_TIMEOUT_SECONDS));
    }
}
