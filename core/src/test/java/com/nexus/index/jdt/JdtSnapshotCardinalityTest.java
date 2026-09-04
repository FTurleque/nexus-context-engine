package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtSnapshotCardinalityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsDocumentSymbolsBeyondSnapshotLimit() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("symbols-project"));
        Path app = write(root, "src/App.java", "class App {\n  void run() {}\n}\n");
        String appUri = app.toRealPath().toUri().toString();

        JdtLanguageServerCodeIntelligenceProvider.Session session = new StubSession(appUri, null, true, false);
        JdtLanguageServerCodeIntelligenceProvider provider = provider(
                session,
                new JdtLanguageServerCodeIntelligenceProvider.SnapshotLimits(1, 10));

        IOException failure = assertThrows(IOException.class, () -> provider.analyze(root));
        assertTrue(failure.getMessage().contains("1 symboles"), failure.getMessage());
    }

    @Test
    void rejectsRelationsBeyondSnapshotLimit() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("relations-project"));
        Path app = write(root, "src/App.java", "class App {}\n");
        Path use = write(root, "src/Use.java", "line1\nline2\nline3\n");
        String appUri = app.toRealPath().toUri().toString();
        String useUri = use.toRealPath().toUri().toString();

        JdtLanguageServerCodeIntelligenceProvider.Session session = new StubSession(appUri, useUri, false, true);
        JdtLanguageServerCodeIntelligenceProvider provider = provider(
                session,
                new JdtLanguageServerCodeIntelligenceProvider.SnapshotLimits(10, 2));

        IOException failure = assertThrows(IOException.class, () -> provider.analyze(root));
        assertTrue(failure.getMessage().contains("2 relations"), failure.getMessage());
    }

    private JdtLanguageServerCodeIntelligenceProvider provider(
            JdtLanguageServerCodeIntelligenceProvider.Session session,
            JdtLanguageServerCodeIntelligenceProvider.SnapshotLimits limits) {
        var configuration = new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                temporaryDirectory.resolve("jdtls"),
                temporaryDirectory.resolve("workspaces"),
                "java",
                Duration.ofSeconds(5),
                10);
        return new JdtLanguageServerCodeIntelligenceProvider(
                configuration,
                (ignored, root) -> session,
                JSON,
                limits);
    }

    private static Path write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static ObjectNode range(int startLine, int endLine) {
        ObjectNode range = JSON.createObjectNode();
        range.set("start", JSON.createObjectNode().put("line", startLine).put("character", 0));
        range.set("end", JSON.createObjectNode().put("line", endLine).put("character", 1));
        return range;
    }

    private static ObjectNode symbol(String name, int line) {
        ObjectNode symbol = JSON.createObjectNode();
        symbol.put("name", name);
        symbol.put("kind", 5);
        symbol.set("range", range(line, line));
        symbol.set("selectionRange", range(line, line));
        return symbol;
    }

    private static ObjectNode location(String uri, int line) {
        ObjectNode location = JSON.createObjectNode();
        location.put("uri", uri);
        location.set("range", range(line, line));
        return location;
    }

    private static final class StubSession implements JdtLanguageServerCodeIntelligenceProvider.Session {
        private final String appUri;
        private final String useUri;
        private final boolean returnTwoSymbols;
        private final boolean returnThreeReferences;

        private StubSession(
                String appUri,
                String useUri,
                boolean returnTwoSymbols,
                boolean returnThreeReferences) {
            this.appUri = appUri;
            this.useUri = useUri;
            this.returnTwoSymbols = returnTwoSymbols;
            this.returnThreeReferences = returnThreeReferences;
        }

        @Override
        public void initialize() {
        }

        @Override
        public JsonNode request(String method, JsonNode params) {
            if ("textDocument/documentSymbol".equals(method)) {
                String uri = params.path("textDocument").path("uri").asText();
                ArrayNode symbols = JSON.createArrayNode();
                if (appUri.equals(uri)) {
                    symbols.add(symbol("App", 0));
                    if (returnTwoSymbols) {
                        symbols.add(symbol("Second", 1));
                    }
                }
                return symbols;
            }
            if ("textDocument/references".equals(method) && returnThreeReferences) {
                return JSON.createArrayNode()
                        .add(location(useUri, 0))
                        .add(location(useUri, 1))
                        .add(location(useUri, 2));
            }
            return JSON.createArrayNode();
        }

        @Override
        public void notify(String method, JsonNode params) {
        }

        @Override
        public void close() {
        }
    }
}
