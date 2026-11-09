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
            throw new IllegalArgumentException("Le modèle d'embedding Ollama ne peut pas être vide");
        }
        if (dimensions <= 0 || dimensions > 1024) {
            throw new IllegalArgumentException("La dimension d'embedding Ollama doit être comprise entre 1 et 1024");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Le timeout Ollama doit être strictement positif");
        }
        if (maxResponseBytes <= 0 || maxResponseBytes > MAX_INTERNAL_RESPONSE_BYTES) {
            throw new IllegalArgumentException(
                    "maxResponseBytes doit être compris entre 1 et " + MAX_INTERNAL_RESPONSE_BYTES);
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
        return embedQuery(query, timeout);
    }

    /**
     * Le délai de l'appel est le plus petit du timeout configuré et du temps restant
     * accordé par l'appelant, avec un minimum d'une milliseconde.
     */
    @Override
    public float[] embedQuery(String query, Duration maxWait) throws IOException {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(maxWait, "maxWait");
        if (query.isBlank()) {
            throw new IllegalArgumentException("La requête à vectoriser ne peut pas être vide");
        }
        Duration callTimeout = cappedTimeout(maxWait);
        String text = QWEN3_EMBEDDING_MODEL.matcher(model).find()
                ? "Instruct: " + QWEN3_RETRIEVAL_INSTRUCTION + "\nQuery:" + query
                : query;
        return embedAll(List.of(text), callTimeout).getFirst();
    }

    private Duration cappedTimeout(Duration maxWait) {
        Duration floor = Duration.ofMillis(1);
        Duration capped = maxWait.compareTo(timeout) < 0 ? maxWait : timeout;
        return capped.compareTo(floor) < 0 ? floor : capped;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) throws IOException {
        return embedAll(texts, timeout);
    }

    private List<float[]> embedAll(List<String> texts, Duration callTimeout) throws IOException {
        Objects.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        requireNonBlank(texts);

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "input", texts));
        HttpRequest request = HttpRequest.newBuilder(embedEndpoint)
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response = send(request, callTimeout);
        String responseBody = new String(response.body(), StandardCharsets.UTF_8);
        requireSuccessfulStatus(response.statusCode(), responseBody);
        return parseEmbeddings(responseBody, texts.size());
    }

    private static void requireNonBlank(List<String> texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Le texte à vectoriser ne peut pas être vide");
            }
        }
    }

    /** Envoie la requête avec un corps borné et annule l'échange quelle que soit l'issue. */
    private HttpResponse<byte[]> send(HttpRequest request, Duration callTimeout) throws IOException {
        AtomicReference<BoundedBodySubscriber> subscriber = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        var exchange = httpClient.sendAsync(request, info -> {
            var body = new BoundedBodySubscriber(maxResponseBytes);
            subscriber.set(body);
            if (cancelled.get()) body.cancel();
            return body;
        });
        try {
            // Cette future ne termine qu'après le corps entier, contrairement à ofInputStream.
            return exchange.get(callTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw timeoutFailure(exception, callTimeout);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HttpTimeoutException) throw timeoutFailure(cause, callTimeout);
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
    }

    private static void requireSuccessfulStatus(int statusCode, String responseBody) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            String message = "Ollama /api/embed a répondu HTTP " + statusCode
                    + " : " + abbreviate(responseBody, 500);
            if (statusCode == 429 || statusCode >= 500) {
                throw new EmbeddingProviderUnavailableException(message);
            }
            throw new IOException(message);
        }
    }

    private List<float[]> parseEmbeddings(String responseBody, int expectedCount) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        if (root == null) {
            throw new IOException("Réponse Ollama invalide : body JSON vide pour /api/embed");
        }
        JsonNode embeddings = root.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != expectedCount) {
            throw new IOException(
                    "Réponse Ollama invalide : " + embeddings.size()
                            + " embedding(s) pour " + expectedCount + " entrée(s)");
        }
        List<float[]> vectors = new ArrayList<>(expectedCount);
        for (int vectorIndex = 0; vectorIndex < embeddings.size(); vectorIndex++) {
            vectors.add(parseVector(embeddings.get(vectorIndex), vectorIndex));
        }
        return List.copyOf(vectors);
    }

    private static EmbeddingProviderUnavailableException timeoutFailure(Throwable cause, Duration callTimeout) {
        return new EmbeddingProviderUnavailableException(
                "Ollama /api/embed indisponible : délai dépassé après " + callTimeout.toMillis() + " ms", cause);
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
