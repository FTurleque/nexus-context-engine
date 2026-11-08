package com.nexus.application;

import com.nexus.search.semantic.SemanticSearchConfiguration;
import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Une dimension d'embedding Ollama hors de l'intervalle 1 à 1024 est refusée en français, que
 * la valeur vienne de l'environnement lu par la composition ou d'un appel direct au provider.
 */
class OllamaEmbeddingProviderUserMessageTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "1025"})
    void outOfRangeConfiguredDimensionsAreRejectedInFrenchByTheComposition(String dimensions) {
        Map<String, String> environment = Map.of(
                SemanticSearchConfiguration.PROVIDER_ENV, "ollama",
                SemanticSearchConfiguration.OLLAMA_DIMENSIONS_ENV, dimensions);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NexusApplicationFactory.semanticSearchConfiguration(environment));

        assertEquals(
                SemanticSearchConfiguration.OLLAMA_DIMENSIONS_ENV + " doit être compris entre 1 et 1024",
                failure.getMessage());
    }

    @Test
    void outOfRangeProviderDimensionsAreRejectedInFrench() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new OllamaEmbeddingProvider(
                        OllamaEmbeddingProvider.DEFAULT_BASE_URI,
                        OllamaEmbeddingProvider.DEFAULT_MODEL,
                        1025,
                        Duration.ofSeconds(5)));

        assertEquals("La dimension d'embedding Ollama doit être comprise entre 1 et 1024", failure.getMessage());
    }
}
