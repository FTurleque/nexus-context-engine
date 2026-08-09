package com.nexus.search.semantic;

import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import com.nexus.search.semantic.ollama.OllamaEndpointResolver;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Configuration explicite de la capacité sémantique. */
public final class SemanticSearchConfiguration {

    public static final double DEFAULT_SEMANTIC_RRF_WEIGHT = 8.0d;
    public static final double MAX_SEMANTIC_RRF_WEIGHT = 10.0d;

    public static final String PROVIDER_ENV = "NEXUS_SEMANTIC_PROVIDER";
    public static final String RRF_WEIGHT_ENV = "NEXUS_SEMANTIC_RRF_WEIGHT";
    public static final String OLLAMA_BASE_URL_ENV = "NEXUS_OLLAMA_BASE_URL";
    /**
     * Indice de runtime positionné par le template Docker Compose ({@code NEXUS_RUNTIME=docker}).
     * Il permet de résoudre une URL Ollama de bouclage vers {@code host.docker.internal} lorsque
     * NEXUS s'exécute en conteneur, y compris si le {@code .env} contient encore {@code 127.0.0.1}.
     */
    public static final String RUNTIME_ENV = "NEXUS_RUNTIME";
    public static final String OLLAMA_MODEL_ENV = "NEXUS_OLLAMA_EMBEDDING_MODEL";
    public static final String OLLAMA_DIMENSIONS_ENV = "NEXUS_OLLAMA_EMBEDDING_DIMENSIONS";
    public static final String OLLAMA_TIMEOUT_SECONDS_ENV = "NEXUS_OLLAMA_TIMEOUT_SECONDS";

    private static final SemanticSearchConfiguration DISABLED =
            new SemanticSearchConfiguration(null, DEFAULT_SEMANTIC_RRF_WEIGHT);

    private final EmbeddingProvider embeddingProvider;
    private final double semanticRrfWeight;

    private SemanticSearchConfiguration(EmbeddingProvider embeddingProvider, double semanticRrfWeight) {
        if (!Double.isFinite(semanticRrfWeight)
                || semanticRrfWeight <= 0.0d
                || semanticRrfWeight > MAX_SEMANTIC_RRF_WEIGHT) {
            throw new IllegalArgumentException(
                    "semanticRrfWeight must be greater than 0.0 and at most " + MAX_SEMANTIC_RRF_WEIGHT);
        }
        this.embeddingProvider = embeddingProvider;
        this.semanticRrfWeight = semanticRrfWeight;
    }

    public static SemanticSearchConfiguration disabled() {
        return DISABLED;
    }

    public static SemanticSearchConfiguration enabled(EmbeddingProvider embeddingProvider) {
        return enabled(embeddingProvider, DEFAULT_SEMANTIC_RRF_WEIGHT);
    }

    public static SemanticSearchConfiguration enabled(
            EmbeddingProvider embeddingProvider,
            double semanticRrfWeight) {
        return new SemanticSearchConfiguration(
                Objects.requireNonNull(embeddingProvider, "embeddingProvider"),
                semanticRrfWeight);
    }

    /**
     * Résout l'opt-in opérationnel commun à tous les adaptateurs.
     *
     * <p>Sans {@code NEXUS_SEMANTIC_PROVIDER}, la capacité reste strictement
     * désactivée. La seule valeur supportée actuellement est {@code ollama}.</p>
     */
    public static SemanticSearchConfiguration fromEnvironment() {
        String provider = environment(PROVIDER_ENV, "");
        if (provider.isBlank() || "disabled".equalsIgnoreCase(provider) || "off".equalsIgnoreCase(provider)) {
            return disabled();
        }
        if (!"ollama".equals(provider.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    PROVIDER_ENV + " supporte uniquement 'ollama' ou 'disabled' actuellement");
        }

        URI configuredBaseUri = URI.create(environment(
                OLLAMA_BASE_URL_ENV,
                OllamaEmbeddingProvider.DEFAULT_BASE_URI.toString()));
        boolean dockerRuntime = "docker".equalsIgnoreCase(environment(RUNTIME_ENV, ""));
        URI baseUri = OllamaEndpointResolver.resolveForRuntime(configuredBaseUri, dockerRuntime);
        String model = environment(OLLAMA_MODEL_ENV, OllamaEmbeddingProvider.DEFAULT_MODEL);
        int dimensions = positiveInt(
                OLLAMA_DIMENSIONS_ENV,
                environment(OLLAMA_DIMENSIONS_ENV, Integer.toString(OllamaEmbeddingProvider.DEFAULT_DIMENSIONS)));
        long timeoutSeconds = positiveLong(
                OLLAMA_TIMEOUT_SECONDS_ENV,
                environment(OLLAMA_TIMEOUT_SECONDS_ENV,
                        Long.toString(OllamaEmbeddingProvider.DEFAULT_TIMEOUT.toSeconds())));
        double weight = positiveDouble(
                RRF_WEIGHT_ENV,
                environment(RRF_WEIGHT_ENV, Double.toString(DEFAULT_SEMANTIC_RRF_WEIGHT)));

        OllamaEmbeddingProvider ollama = new OllamaEmbeddingProvider(
                baseUri,
                model,
                dimensions,
                Duration.ofSeconds(timeoutSeconds));
        return enabled(ollama, weight);
    }

    public boolean enabled() {
        return embeddingProvider != null;
    }

    public Optional<EmbeddingProvider> embeddingProvider() {
        return Optional.ofNullable(embeddingProvider);
    }

    public double semanticRrfWeight() {
        return semanticRrfWeight;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int positiveInt(String name, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " doit être strictement positif");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier", exception);
        }
    }

    private static long positiveLong(String name, String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " doit être strictement positif");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un entier", exception);
        }
    }

    private static double positiveDouble(String name, String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed <= 0.0d || parsed > MAX_SEMANTIC_RRF_WEIGHT) {
                throw new IllegalArgumentException(
                        name + " doit être > 0 et <= " + MAX_SEMANTIC_RRF_WEIGHT);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un nombre", exception);
        }
    }
}
