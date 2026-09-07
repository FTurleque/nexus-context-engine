package com.nexus.search.semantic.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingProviderQueryInstructionTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void qwen3AddsRetrievalInstructionOnlyToQueries() throws Exception {
        List<String> capturedInputs = new ArrayList<>();
        URI baseUri = serveAndCaptureInputs(capturedInputs);
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                baseUri,
                "qwen3-embedding:0.6b",
                1,
                Duration.ofSeconds(2));

        provider.embedQuery("find the Git context documentation");
        provider.embedAll(List.of("path: docs/developer/git-context.md\nContexte Git local"));

        assertEquals(2, capturedInputs.size());
        assertTrue(capturedInputs.get(0).startsWith(
                "Instruct: Given a software repository search query, retrieve the most relevant "));
        assertTrue(capturedInputs.get(0).endsWith("\nQuery:find the Git context documentation"));
        assertEquals(
                "path: docs/developer/git-context.md\nContexte Git local",
                capturedInputs.get(1));
    }

    @Test
    void otherOllamaModelsKeepQueryTextUnmodified() throws Exception {
        List<String> capturedInputs = new ArrayList<>();
        URI baseUri = serveAndCaptureInputs(capturedInputs);
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                baseUri,
                "fixture-embedding-model:latest",
                1,
                Duration.ofSeconds(2));

        provider.embedQuery("raw query text");

        assertEquals(List.of("raw query text"), capturedInputs);
    }

    private URI serveAndCaptureInputs(List<String> capturedInputs) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/embed", exchange -> respond(exchange, objectMapper, capturedInputs));
        server.start();
        InetSocketAddress address = server.getAddress();
        String host = address.getAddress() instanceof java.net.Inet6Address
                ? "[" + address.getAddress().getHostAddress() + "]"
                : address.getAddress().getHostAddress();
        return URI.create("http://" + host + ":" + address.getPort());
    }

    private static void respond(
            HttpExchange exchange,
            ObjectMapper objectMapper,
            List<String> capturedInputs) throws IOException {
        try (exchange) {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            JsonNode inputs = request.path("input");
            for (JsonNode input : inputs) {
                capturedInputs.add(input.asText());
            }
            byte[] response = "{\"embeddings\":[[1.0]]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        }
    }
}
