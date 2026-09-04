package com.nexus.search.semantic.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.search.semantic.EmbeddingProviderUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingProviderResponseLimitTest {

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

    @Test
    void boundedReaderClosesInputStreamOnSuccess() throws Exception {
        TrackingInputStream input = new TrackingInputStream(json("abc"));

        byte[] result = OllamaEmbeddingProvider.readBoundedResponse(
                input,
                3,
                URI.create("http://localhost/api/embed"));

        assertEquals("abc", new String(result, StandardCharsets.UTF_8));
        assertTrue(input.closed);
    }

    @Test
    void boundedReaderClosesInputStreamOnOverflow() {
        TrackingInputStream input = new TrackingInputStream(json("abcd"));

        assertThrows(
                IOException.class,
                () -> OllamaEmbeddingProvider.readBoundedResponse(
                        input,
                        3,
                        URI.create("http://localhost/api/embed")));

        assertTrue(input.closed);
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

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
