package com.nexus.search.semantic.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.search.semantic.EmbeddingProviderUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingProviderResponseLimitTest {

    @Test
    void deadlineCoversStalledBodyAndSameProviderRecovers() throws Exception {
        CountDownLatch headers = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", exchange -> {
            if (calls.incrementAndGet() != 1) {
                respond(exchange, 200, json("{\"embeddings\":[[1.0]]}"), false, Duration.ZERO);
                return;
            }
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headers.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        var provider = provider(serverUri(server), 1, Duration.ofMillis(800), 1024);
        long started = System.nanoTime();
        try {
            var failure = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(4),
                    () -> assertThrows(EmbeddingProviderUnavailableException.class, () -> provider.embed("stalled")));
            assertEquals(0, headers.getCount(), "La panne doit survenir après les headers");
            assertTrue(failure.getMessage().contains("délai dépassé"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4000);
        } finally {
            release.countDown();
        }
        assertArrayEquals(new float[]{1.0f}, provider.embed("recovered"));
    }

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        // Un test d'interruption doit toujours rendre le thread JUnit propre.
        Thread.interrupted();
    }

    @Test
    void defaultLimitLeavesMarginForMaximumDefaultBatchShape() {
        int approximateMaximumFloatJsonBytes = 32 * 1024 * 16;

        assertEquals(OllamaEmbeddingProvider.DEFAULT_MAX_RESPONSE_BYTES, 1024 * 1024);
        assertTrue(OllamaEmbeddingProvider.DEFAULT_MAX_RESPONSE_BYTES >= approximateMaximumFloatJsonBytes * 2);
    }

    @Test
    void acceptsNormalEmbeddingResponse() throws Exception {
        byte[] body = json("{\"embeddings\":[[0.25,-0.5]]}");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        float[] vector = provider(baseUri, 2, Duration.ofSeconds(2), 1024).embed("normal");

        assertArrayEquals(new float[]{0.25f, -0.5f}, vector, 0.0f);
    }

    @Test
    void acceptsResponseStrictlyUnderLimit() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        float[] vector = provider(baseUri, 1, Duration.ofSeconds(2), body.length + 1).embed("under");

        assertEquals(1.0f, vector[0]);
    }

    @Test
    void acceptsResponseExactlyAtLimit() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        float[] vector = provider(baseUri, 1, Duration.ofSeconds(2), body.length).embed("exact");

        assertEquals(1.0f, vector[0]);
    }

    @Test
    void rejectsResponseOneByteAboveLimitBeforeJsonMaterialization() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        int limit = body.length - 1;
        URI baseUri = serve(200, body, false, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 1, Duration.ofSeconds(2), limit).embed("overflow"));

        assertOverflow(exception, limit);
    }

    @Test
    void rejectsChunkedResponseAboveLimit() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]],\"padding\":\"" + "x".repeat(4096) + "\"}");
        URI baseUri = serve(200, body, true, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 1, Duration.ofSeconds(2), 128).embed("chunked"));

        assertOverflow(exception, 128);
    }

    @Test
    void reportsSmallHttp500BodyAfterBoundedRead() throws Exception {
        byte[] body = json("{\"error\":\"small failure\"}");
        URI baseUri = serve(500, body, false, Duration.ZERO);

        EmbeddingProviderUnavailableException exception = assertThrows(
                EmbeddingProviderUnavailableException.class,
                () -> provider(baseUri, 1, Duration.ofSeconds(2), 1024).embed("http500"));

        assertTrue(exception.getMessage().contains("Ollama /api/embed"));
        assertTrue(exception.getMessage().contains("HTTP 500"));
        assertTrue(exception.getMessage().contains("small failure"));
    }

    @Test
    void rejectsHugeHttp500BodyWithoutIncludingItInError() throws Exception {
        String secret = "NXA08-HUGE-ERROR-SECRET";
        byte[] body = json(secret + "x".repeat(16_384));
        URI baseUri = serve(500, body, true, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 1, Duration.ofSeconds(2), 256).embed("http500-huge"));

        assertOverflow(exception, 256);
        assertFalse(exception.getMessage().contains(secret));
        assertTrue(exception.getMessage().length() < 512);
    }

    @Test
    void rejectsInvalidJsonThatRemainsWithinLimit() throws Exception {
        byte[] body = json("{not-json");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 1, Duration.ofSeconds(2), body.length).embed("invalid-json"));

        assertFalse(exception.getMessage().contains("byte limit"));
    }

    @Test
    void rejectsVectorSmallerThanConfiguredDimensions() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 2, Duration.ofSeconds(2), 1024).embed("small-vector"));

        assertTrue(exception.getMessage().contains("dimension invalide"));
        assertFalse(exception instanceof EmbeddingProviderUnavailableException);
    }

    @Test
    void rejectsVectorLargerThanConfiguredDimensions() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0,2.0,3.0]]}");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 2, Duration.ofSeconds(2), 1024).embed("large-vector"));

        assertTrue(exception.getMessage().contains("dimension invalide"));
        assertFalse(exception instanceof EmbeddingProviderUnavailableException);
    }

    @Test
    void rejectsFiniteDoubleThatOverflowsFloatToNonFinite() throws Exception {
        byte[] body = json("{\"embeddings\":[[3.5e38]]}");
        URI baseUri = serve(200, body, false, Duration.ZERO);

        IOException exception = assertThrows(
                IOException.class,
                () -> provider(baseUri, 1, Duration.ofSeconds(2), 1024).embed("non-finite"));

        assertTrue(exception.getMessage().contains("valeur non finie"));
        assertFalse(exception instanceof EmbeddingProviderUnavailableException);
    }

    @Test
    void reportsRequestTimeoutWithStableDiagnostic() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        URI baseUri = serve(200, body, false, Duration.ofMillis(400));

        EmbeddingProviderUnavailableException exception = assertThrows(
                EmbeddingProviderUnavailableException.class,
                () -> provider(baseUri, 1, Duration.ofMillis(50), 1024).embed("timeout"));

        assertEquals(
                "Ollama /api/embed indisponible : délai dépassé après 50 ms",
                exception.getMessage());
    }

    @Test
    void reportsTimeoutWhenHeadersArriveButBodyStalls() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        CountDownLatch releaseBody = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body[0]);
                    output.flush();
                    // Le corps reste suspendu jusqu'à la fin du test : seul le délai du provider peut conclure.
                    releaseBody.await(10, TimeUnit.SECONDS);
                    output.write(body, 1, body.length - 1);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException expectedAfterClientTimeout) {
                // La fermeture du flux par le client est attendue après expiration.
            }
        });
        server.start();
        HeaderGatedHttpClient client = new HeaderGatedHttpClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                client, new ObjectMapper(), serverUri(server), "fixture-model", 1, Duration.ofMillis(150), 1024);

        try {
            EmbeddingProviderUnavailableException thrown = assertTimeoutPreemptively(
                    HeaderGatedHttpClient.HEADER_WAIT,
                    () -> assertThrows(EmbeddingProviderUnavailableException.class,
                            () -> provider.embed("body-timeout")));

            assertTrue(client.headersReceived(), "Le client doit avoir reçu les en-têtes avant le délai");
            assertInstanceOf(TimeoutException.class, thrown.getCause(),
                    "Le délai doit expirer pendant la lecture du corps, pas pendant l'attente des en-têtes");
            assertEquals(
                    "Ollama /api/embed indisponible : délai dépassé après 150 ms",
                    thrown.getMessage());
        } finally {
            releaseBody.countDown();
        }
    }

    /**
     * Client qui ne rend la future à l'appelant qu'une fois les en-têtes de réponse reçus.
     * Le délai que le provider mesure sur cette future ne couvre alors que la lecture du
     * corps, quel que soit le temps d'établissement de la connexion. Le timeout de requête
     * (phase des en-têtes) est porté à {@link #HEADER_WAIT} : cette phase est couverte par
     * {@code reportsRequestTimeoutWithStableDiagnostic}.
     */
    private static final class HeaderGatedHttpClient extends HttpClient {

        static final Duration HEADER_WAIT = Duration.ofSeconds(10);

        private final HttpClient delegate;
        private final CompletableFuture<Void> headers = new CompletableFuture<>();

        HeaderGatedHttpClient(HttpClient delegate) {
            this.delegate = delegate;
        }

        boolean headersReceived() {
            return headers.isDone();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            HttpRequest withHeaderWait = HttpRequest.newBuilder(request, (name, value) -> true)
                    .timeout(HEADER_WAIT)
                    .build();
            CompletableFuture<HttpResponse<T>> response = delegate.sendAsync(withHeaderWait, info -> {
                headers.complete(null);
                return handler.apply(info);
            });
            try {
                CompletableFuture.anyOf(headers, response).get(HEADER_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException exception) {
                // La future rendue porte l'échec ; headersReceived() reste faux et le test le signale.
            }
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            return delegate.send(request, handler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }
    }

    @Test
    void sameProviderRecoversAfterTransientServiceUnavailableResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                respond(exchange, 503, json("{\"error\":\"temporarily unavailable\"}"), false, Duration.ZERO);
            } else {
                respond(exchange, 200, json("{\"embeddings\":[[1.0]]}"), false, Duration.ZERO);
            }
        });
        server.start();
        URI baseUri = serverUri(server);
        OllamaEmbeddingProvider provider = provider(baseUri, 1, Duration.ofSeconds(2), 1024);

        EmbeddingProviderUnavailableException unavailable = assertThrows(
                EmbeddingProviderUnavailableException.class,
                () -> provider.embed("first"));
        assertTrue(unavailable.getMessage().contains("HTTP 503"));

        float[] recovered = provider.embed("second");
        assertArrayEquals(new float[]{1.0f}, recovered, 0.0f);
        assertEquals(2, calls.get());
    }

    @Test
    void interruptionIsRestoredAndReportedAsIOException() throws Exception {
        byte[] body = json("{\"embeddings\":[[1.0]]}");
        URI baseUri = serve(200, body, false, Duration.ofMillis(400));
        OllamaEmbeddingProvider provider = provider(baseUri, 1, Duration.ofSeconds(2), 1024);

        try {
            Thread.currentThread().interrupt();
            IOException exception = assertThrows(IOException.class, () -> provider.embed("interrupt"));

            assertTrue(exception.getMessage().contains("Appel Ollama interrompu"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private OllamaEmbeddingProvider provider(
            URI baseUri,
            int dimensions,
            Duration timeout,
            int maxResponseBytes) {
        return new OllamaEmbeddingProvider(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(),
                baseUri,
                "fixture-model",
                dimensions,
                timeout,
                maxResponseBytes);
    }

    private URI serve(int status, byte[] body, boolean chunked, Duration delay) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", exchange -> respond(exchange, status, body, chunked, delay));
        server.start();
        return serverUri(server);
    }

    private static URI serverUri(HttpServer httpServer) {
        InetSocketAddress address = httpServer.getAddress();
        String host = address.getAddress() instanceof java.net.Inet6Address
                ? "[" + address.getAddress().getHostAddress() + "]"
                : address.getAddress().getHostAddress();
        return URI.create("http://" + host + ":" + address.getPort());
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            byte[] body,
            boolean chunked,
            Duration delay) throws IOException {
        try (exchange) {
            try {
                exchange.getRequestBody().readAllBytes();
                if (!delay.isZero()) {
                    new CountDownLatch(1).await(delay.toMillis(), TimeUnit.MILLISECONDS);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, chunked ? 0L : body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // Timeouts/early client close are expected in timeout and overflow tests.
            }
        }
    }

    private static void assertOverflow(IOException exception, int limit) {
        assertTrue(exception.getMessage().contains("Ollama"));
        assertTrue(exception.getMessage().contains("/api/embed"));
        assertTrue(exception.getMessage().contains(Integer.toString(limit)));
        assertTrue(exception.getMessage().contains("byte limit"));
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

}
