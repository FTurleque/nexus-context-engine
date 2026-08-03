package com.nexus.search.semantic.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.search.semantic.EmbeddingProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider d'embeddings Ollama explicitement opt-in. */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    public static final URI DEFAULT_BASE_URI = URI.create("http://localhost:11434");
    public static final String DEFAULT_MODEL = "qwen3-embedding:0.6b";
    public static final int DEFAULT_DIMENSIONS = 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI embedEndpoint;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    public OllamaEmbeddingProvider() {
        this(DEFAULT_BASE_URI, DEFAULT_MODEL, DEFAULT_DIMENSIONS, DEFAULT_TIMEOUT);
    }

    public OllamaEmbeddingProvider(
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Objects.requireNonNull(timeout, "timeout"))
                        .build(),
                new ObjectMapper(),
                baseUri,
                model,
                dimensions,
                timeout);
    }

    OllamaEmbeddingProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(baseUri, "baseUri");
        this.model = Objects.requireNonNull(model, "model").trim();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (this.model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (dimensions <= 0 || dimensions > 1024) {
            throw new IllegalArgumentException("dimensions must be between 1 and 1024");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be greater than zero");
        }
        this.dimensions = dimensions;
        String normalizedBase = baseUri.toString().replaceAll("/+$", "");
        this.embedEndpoint = URI.create(normalizedBase + "/api/embed");
    }

    @Override
    public String modelId() {
        return "ollama/" + model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) throws IOException {
        return embedAll(List.of(text)).getFirst();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) throws IOException {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("embedding text must not be blank");
            }
        }

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "input", texts));
        HttpRequest request = HttpRequest.newBuilder(embedEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Appel Ollama interrompu", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(
                    "Ollama /api/embed a répondu HTTP " + response.statusCode()
                            + " : " + abbreviate(response.body(), 500));
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode embeddings = root.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != texts.size()) {
            throw new IOException(
                    "Réponse Ollama invalide : " + embeddings.size()
                            + " embedding(s) pour " + texts.size() + " entrée(s)");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int vectorIndex = 0; vectorIndex < embeddings.size(); vectorIndex++) {
            vectors.add(parseVector(embeddings.get(vectorIndex), vectorIndex));
        }
        return List.copyOf(vectors);
    }

    private float[] parseVector(JsonNode embedding, int vectorIndex) throws IOException {
        if (!embedding.isArray() || embedding.size() != dimensions) {
            throw new IOException(
                    "Le modèle " + model + " a produit une dimension invalide pour le vecteur " + vectorIndex);
        }
        float[] vector = new float[dimensions];
        for (int index = 0; index < dimensions; index++) {
            JsonNode value = embedding.get(index);
            if (value == null || !value.isNumber()) {
                throw new IOException(
                        "Réponse Ollama invalide : valeur non numérique dans le vecteur " + vectorIndex
                                + " à l'index " + index);
            }
            vector[index] = value.floatValue();
            if (!Float.isFinite(vector[index])) {
                throw new IOException(
                        "Réponse Ollama invalide : valeur non finie dans le vecteur " + vectorIndex
                                + " à l'index " + index);
            }
        }
        return vector;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }
}
