package com.nexus.search.semantic.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.EmbeddingProviderUnavailableException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Provider d'embeddings Ollama explicitement opt-in. */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    public static final URI DEFAULT_BASE_URI = URI.create("http://localhost:11434");
    public static final String DEFAULT_MODEL = "qwen3-embedding:0.6b";
    public static final int DEFAULT_DIMENSIONS = 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    /**
     * 32 entrées × 1024 dimensions représentent au plus 32 768 floats par lot.
     * Un plafond de 1 MiB laisse plus de deux fois l'espace nécessaire à leur
     * représentation JSON usuelle, plus les métadonnées Ollama, tout en bornant
     * strictement la matérialisation d'une réponse anormale.
     */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    private static final int MAX_INTERNAL_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern QWEN3_EMBEDDING_MODEL =
            Pattern.compile("(?i)(?:^|/)qwen3-embedding(?::|$)");
    private static final String QWEN3_RETRIEVAL_INSTRUCTION =
            "Given a software repository search query, retrieve the most relevant source code or documentation passage that answers the query";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI embedEndpoint;
    private final String model;
    private final int dimensions;
    private final Duration timeout;
    private final int maxResponseBytes;

    public OllamaEmbeddingProvider() {
        this(DEFAULT_BASE_URI, DEFAULT_MODEL, DEFAULT_DIMENSIONS, DEFAULT_TIMEOUT);
    }

    /**
     * Construit un provider en appliquant la politique sûre par défaut : HTTP est
     * accepté uniquement pour une adresse de bouclage et les credentials dans
     * l'URI sont refusés.
     */
    public OllamaEmbeddingProvider(
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout) {
        this(baseUri, model, dimensions, timeout, false);
    }

    /**
     * Variante explicite pour les déploiements qui ont volontairement autorisé
     * un endpoint HTTP distant. L'opt-in reste porté par l'appelant et toutes les
     * autres validations d'URI restent actives.
     */
    public OllamaEmbeddingProvider(
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout,
            boolean allowInsecureRemoteEndpoint) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Objects.requireNonNull(timeout, "timeout"))
                        .build(),
                new ObjectMapper(),
                baseUri,
                model,
                dimensions,
                timeout,
                DEFAULT_MAX_RESPONSE_BYTES,
                allowInsecureRemoteEndpoint);
    }

    OllamaEmbeddingProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout) {
        this(
                httpClient,
                objectMapper,
                baseUri,
                model,
                dimensions,
                timeout,
                DEFAULT_MAX_RESPONSE_BYTES,
                false);
    }

    OllamaEmbeddingProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout,
            int maxResponseBytes) {
        this(
                httpClient,
                objectMapper,
                baseUri,
                model,
                dimensions,
                timeout,
                maxResponseBytes,
                false);
    }

    OllamaEmbeddingProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            String model,
            int dimensions,
            Duration timeout,
            int maxResponseBytes,
            boolean allowInsecureRemoteEndpoint) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(baseUri, "baseUri");
        OllamaEndpointResolver.validateEndpoint(baseUri, allowInsecureRemoteEndpoint);
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
        if (maxResponseBytes <= 0 || maxResponseBytes > MAX_INTERNAL_RESPONSE_BYTES) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be between 1 and " + MAX_INTERNAL_RESPONSE_BYTES);
        }
        this.dimensions = dimensions;
        this.maxResponseBytes = maxResponseBytes;
        String normalizedBase = TRAILING_SLASHES.matcher(baseUri.toString()).replaceAll("");
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
    public float[] embedQuery(String query) throws IOException {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("embedding query must not be blank");
        }
        if (!QWEN3_EMBEDDING_MODEL.matcher(model).find()) {
            return embed(query);
        }
        return embed("Instruct: " + QWEN3_RETRIEVAL_INSTRUCTION + "\nQuery:" + query);
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

        AtomicReference<BoundedBodySubscriber> subscriber = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        var exchange = httpClient.sendAsync(request, info -> {
            var body = new BoundedBodySubscriber(maxResponseBytes);
            subscriber.set(body);
            if (cancelled.get()) body.cancel();
            return body;
        });
        HttpResponse<byte[]> response;
        try {
            // Cette future ne termine qu'après le corps entier, contrairement à ofInputStream.
            response = exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw timeoutFailure(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpTimeoutException) throw timeoutFailure(cause);
            if (cause instanceof BoundedBodySubscriber.ResponseLimitException overflow) throw overflow;
            throw new EmbeddingProviderUnavailableException(
                    "Ollama /api/embed indisponible : connexion impossible", cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Appel Ollama interrompu", exception);
        } finally {
            cancelled.set(true);
            var body = subscriber.get();
            if (body != null) body.cancel();
            exchange.cancel(true);
        }
        String responseBody = new String(response.body(), StandardCharsets.UTF_8);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "Ollama /api/embed a répondu HTTP " + response.statusCode()
                    + " : " + abbreviate(responseBody, 500);
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new EmbeddingProviderUnavailableException(message);
            }
            throw new IOException(message);
        }

        JsonNode root = objectMapper.readTree(responseBody);
        if (root == null) {
            throw new IOException("Réponse Ollama invalide : body JSON vide pour /api/embed");
        }
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

    private EmbeddingProviderUnavailableException timeoutFailure(Throwable cause) {
        return new EmbeddingProviderUnavailableException(
                "Ollama /api/embed indisponible : délai dépassé après " + timeout.toMillis() + " ms", cause);
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
