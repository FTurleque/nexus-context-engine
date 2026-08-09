package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeIntelligenceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtSymbolRangeValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsDocumentSymbolBeyondCanonicalFile() throws Exception {
        CodeIntelligenceSnapshot snapshot = analyzeWithRange(0, 10, 0, "class Fixture {}");
        assertTrue(snapshot.symbols().isEmpty());
    }

    @Test
    void rejectsDocumentSymbolWithEndBeforeStart() throws Exception {
        CodeIntelligenceSnapshot snapshot = analyzeWithRange(1, 0, 1, "one\ntwo");
        assertTrue(snapshot.symbols().isEmpty());
    }

    private CodeIntelligenceSnapshot analyzeWithRange(
            int startLine,
            int endLine,
            int selectionLine,
            String content) throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project-" + startLine + "-" + endLine));
        Files.writeString(project.resolve("Fixture.java"), content);

        JdtLanguageServerCodeIntelligenceProvider.Configuration configuration =
                new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                        temporaryDirectory.resolve("jdtls"),
                        temporaryDirectory.resolve("workspaces"),
                        "java",
                        Duration.ofSeconds(1),
                        10);
        JdtLanguageServerCodeIntelligenceProvider provider =
                new JdtLanguageServerCodeIntelligenceProvider(
                        configuration,
                        (ignored, root) -> new RangeSession(startLine, endLine, selectionLine),
                        JSON);

        return provider.analyze(project);
    }

    private record RangeSession(int startLine, int endLine, int selectionLine)
            implements JdtLanguageServerCodeIntelligenceProvider.Session {

        @Override
        public void initialize() {
        }

        @Override
        public JsonNode request(String method, JsonNode params) {
            if (!"textDocument/documentSymbol".equals(method)) {
                return JSON.createArrayNode();
            }
            var symbol = JSON.createObjectNode();
            symbol.put("name", "Fixture");
            symbol.put("kind", 5);
            var range = JSON.createObjectNode();
            range.set("start", JSON.createObjectNode().put("line", startLine).put("character", 0));
            range.set("end", JSON.createObjectNode().put("line", endLine).put("character", 1));
            symbol.set("range", range);
            var selection = JSON.createObjectNode();
            selection.set("start", JSON.createObjectNode().put("line", selectionLine).put("character", 0));
            selection.set("end", JSON.createObjectNode().put("line", selectionLine).put("character", 1));
            symbol.set("selectionRange", selection);
            return JSON.createArrayNode().add(symbol);
        }

        @Override
        public void notify(String method, JsonNode params) {
        }

        @Override
        public void close() {
        }
    }
}
