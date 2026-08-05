package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLanguageServerCodeIntelligenceProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesReferencesImplementationsTypeHierarchyAndCallHierarchy() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path base = write(projectRoot, "src/main/java/demo/Base.java", """
                package demo;
                abstract class Base {
                    abstract void run();
                }
                """);
        Path implementation = write(projectRoot, "src/main/java/demo/Impl.java", """
                package demo;
                class Impl extends Base {
                    public void run() {}
                }
                """);

        FakeSession session = new FakeSession(base.toRealPath().toUri().toString(), implementation.toRealPath().toUri().toString());
        JdtLanguageServerCodeIntelligenceProvider.Configuration configuration =
                new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                        temporaryDirectory.resolve("jdtls"),
                        temporaryDirectory.resolve("workspaces"),
                        "java",
                        Duration.ofSeconds(5),
                        20);
        JdtLanguageServerCodeIntelligenceProvider provider =
                new JdtLanguageServerCodeIntelligenceProvider(configuration, (ignored, root) -> session, JSON);

        CodeIntelligenceSnapshot snapshot = provider.analyze(projectRoot);

        assertTrue(session.initialized);
        assertEquals(JdtLanguageServerCodeIntelligenceProvider.SOURCE_PROVIDER, snapshot.sourceProvider());
        assertEquals(4, snapshot.symbols().size());
        assertTrue(snapshot.symbols().stream()
                .allMatch(symbol -> JdtLanguageServerCodeIntelligenceProvider.SOURCE_PROVIDER
                        .equals(symbol.symbol().sourceProvider())));

        List<SymbolRelation> relations = snapshot.relations().stream()
                .map(indexed -> indexed.relation())
                .toList();
        assertTrue(relations.stream().anyMatch(relation -> relation.kind() == RelationKind.REFERENCES));
        assertTrue(relations.stream().anyMatch(relation -> relation.kind() == RelationKind.IMPLEMENTS));
        assertTrue(relations.stream().anyMatch(relation -> relation.kind() == RelationKind.EXTENDS));
        assertTrue(relations.stream().anyMatch(relation -> relation.kind() == RelationKind.CALLS));
        assertTrue(relations.stream().allMatch(relation ->
                JdtLanguageServerCodeIntelligenceProvider.SOURCE_PROVIDER.equals(relation.sourceProvider())));
    }

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static final class FakeSession implements JdtLanguageServerCodeIntelligenceProvider.Session {

        private final String baseUri;
        private final String implementationUri;
        private final List<String> openedUris = new ArrayList<>();
        private boolean initialized;

        private FakeSession(String baseUri, String implementationUri) {
            this.baseUri = baseUri;
            this.implementationUri = implementationUri;
        }

        @Override
        public void initialize() {
            initialized = true;
        }

        @Override
        public JsonNode request(String method, JsonNode params) throws java.io.IOException {
            String uri = params.path("textDocument").path("uri").asText("");
            int line = params.path("position").path("line").asInt(-1);
            return switch (method) {
                case "textDocument/documentSymbol" -> documentSymbols(uri);
                case "textDocument/references" -> references(uri, line);
                case "textDocument/implementation" -> implementations(uri, line);
                case "textDocument/prepareTypeHierarchy" -> prepareTypeHierarchy(uri, line);
                case "typeHierarchy/supertypes" -> supertypes(params.path("item").path("name").asText());
                case "typeHierarchy/subtypes" -> subtypes(params.path("item").path("name").asText());
                case "textDocument/prepareCallHierarchy" -> prepareCallHierarchy(uri, line);
                case "callHierarchy/outgoingCalls" -> outgoingCalls(params.path("item").path("name").asText());
                case "callHierarchy/incomingCalls" -> incomingCalls(params.path("item").path("name").asText());
                default -> JSON.createArrayNode();
            };
        }

        @Override
        public void notify(String method, JsonNode params) {
            if ("textDocument/didOpen".equals(method)) {
                openedUris.add(params.path("textDocument").path("uri").asText());
            }
        }

        @Override
        public void close() {
            // Rien à fermer pour le faux client.
        }

        private JsonNode documentSymbols(String uri) throws java.io.IOException {
            if (baseUri.equals(uri)) {
                return JSON.readTree("""
                        [
                          {
                            "name":"Base",
                            "kind":5,
                            "range":{"start":{"line":1,"character":0},"end":{"line":3,"character":1}},
                            "selectionRange":{"start":{"line":1,"character":15},"end":{"line":1,"character":19}},
                            "children":[
                              {
                                "name":"run",
                                "detail":"() : void",
                                "kind":6,
                                "range":{"start":{"line":2,"character":4},"end":{"line":2,"character":24}},
                                "selectionRange":{"start":{"line":2,"character":18},"end":{"line":2,"character":21}}
                              }
                            ]
                          }
                        ]
                        """);
            }
            return JSON.readTree("""
                    [
                      {
                        "name":"Impl",
                        "kind":5,
                        "range":{"start":{"line":1,"character":0},"end":{"line":3,"character":1}},
                        "selectionRange":{"start":{"line":1,"character":6},"end":{"line":1,"character":10}},
                        "children":[
                          {
                            "name":"run",
                            "detail":"() : void",
                            "kind":6,
                            "range":{"start":{"line":2,"character":4},"end":{"line":2,"character":24}},
                            "selectionRange":{"start":{"line":2,"character":16},"end":{"line":2,"character":19}}
                          }
                        ]
                      }
                    ]
                    """);
        }

        private JsonNode references(String uri, int line) throws java.io.IOException {
            if (baseUri.equals(uri) && (line == 1 || line == 2)) {
                return locationArray(implementationUri, line);
            }
            return JSON.createArrayNode();
        }

        private JsonNode implementations(String uri, int line) throws java.io.IOException {
            if (baseUri.equals(uri) && (line == 1 || line == 2)) {
                return locationArray(implementationUri, line);
            }
            return JSON.createArrayNode();
        }

        private JsonNode prepareTypeHierarchy(String uri, int line) throws java.io.IOException {
            if (line != 1) {
                return JSON.createArrayNode();
            }
            return JSON.createArrayNode().add(typeItem(
                    baseUri.equals(uri) ? "Base" : "Impl",
                    5,
                    uri,
                    1));
        }

        private JsonNode supertypes(String name) throws java.io.IOException {
            if ("Impl".equals(name)) {
                return JSON.createArrayNode().add(typeItem("Base", 5, baseUri, 1));
            }
            return JSON.createArrayNode();
        }

        private JsonNode subtypes(String name) throws java.io.IOException {
            if ("Base".equals(name)) {
                return JSON.createArrayNode().add(typeItem("Impl", 5, implementationUri, 1));
            }
            return JSON.createArrayNode();
        }

        private JsonNode prepareCallHierarchy(String uri, int line) throws java.io.IOException {
            if (line != 2) {
                return JSON.createArrayNode();
            }
            return JSON.createArrayNode().add(typeItem(
                    baseUri.equals(uri) ? "Base.run" : "Impl.run",
                    6,
                    uri,
                    2));
        }

        private JsonNode outgoingCalls(String name) throws java.io.IOException {
            if ("Impl.run".equals(name)) {
                return JSON.createArrayNode().add(JSON.createObjectNode()
                        .set("to", typeItem("Base.run", 6, baseUri, 2)));
            }
            return JSON.createArrayNode();
        }

        private JsonNode incomingCalls(String name) throws java.io.IOException {
            if ("Base.run".equals(name)) {
                return JSON.createArrayNode().add(JSON.createObjectNode()
                        .set("from", typeItem("Impl.run", 6, implementationUri, 2)));
            }
            return JSON.createArrayNode();
        }

        private JsonNode locationArray(String uri, int line) throws java.io.IOException {
            return JSON.readTree("""
                    [{"uri":"%s","range":{"start":{"line":%d,"character":0},"end":{"line":%d,"character":1}}}]
                    """.formatted(uri, line, line));
        }

        private ObjectNode typeItem(String name, int kind, String uri, int line) {
            ObjectNode item = JSON.createObjectNode();
            item.put("name", name);
            item.put("kind", kind);
            item.put("uri", uri);
            item.set("range", range(line));
            item.set("selectionRange", range(line));
            return item;
        }

        private ObjectNode range(int line) {
            ObjectNode value = JSON.createObjectNode();
            value.set("start", JSON.createObjectNode().put("line", line).put("character", 0));
            value.set("end", JSON.createObjectNode().put("line", line).put("character", 1));
            return value;
        }
    }
}
