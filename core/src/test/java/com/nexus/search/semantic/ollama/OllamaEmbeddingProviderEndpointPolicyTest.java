package com.nexus.search.semantic.ollama;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaEmbeddingProviderEndpointPolicyTest {

    private static final String MODEL = "test-embedding";
    private static final int DIMENSIONS = 4;
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void directProviderApiRejectsRemoteHttpByDefault() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OllamaEmbeddingProvider(
                        URI.create("http://example.com:11434"), MODEL, DIMENSIONS, TIMEOUT));
    }

    @Test
    void directProviderApiAcceptsHttpsWithoutAdditionalOptIn() {
        assertDoesNotThrow(() -> new OllamaEmbeddingProvider(
                URI.create("https://example.com:11434"), MODEL, DIMENSIONS, TIMEOUT));
    }

    @Test
    void explicitOptInAllowsRemoteHttpButStillRejectsCredentials() {
        assertDoesNotThrow(() -> new OllamaEmbeddingProvider(
                URI.create("http://example.com:11434"), MODEL, DIMENSIONS, TIMEOUT, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OllamaEmbeddingProvider(
                        URI.create("http://user:secret@example.com:11434"),
                        MODEL,
                        DIMENSIONS,
                        TIMEOUT,
                        true));
    }
}
