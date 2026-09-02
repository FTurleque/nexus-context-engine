package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosCodeIndexImporterTest {

    @Test
    void sourceProviderIsStable(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = baseDocument(mapper, project);

        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter()
                .importPayload(project, mapper.writeValueAsString(document));

        assertEquals("minos", snapshot.sourceProvider());
        assertTrue(snapshot.symbols().isEmpty());
        assertTrue(snapshot.relations().isEmpty());
    }

    @Test
    void mapsVersionedMinosFactsConservatively(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("GreetingPort.ts"), """
                export interface GreetingPort {
                    greet(): string;
                }
                """);
        Files.writeString(source.resolve("Greeter.ts"), """
                export class Greeter implements GreetingPort {
                    greet(): string {
                        return "hello";
                    }
                }
                """);
        Files.writeString(source.resolve("Generic.ts"), "export type Generic = string;\n");
        ObjectMapper mapper = new ObjectMapper();
        String payload = payload(mapper, project, false);

        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter().importPayload(project, payload);

        assertEquals("minos", snapshot.sourceProvider());
        assertEquals(3, snapshot.symbols().size());
        assertEquals(SymbolKind.INTERFACE, snapshot.symbols().getFirst().symbol().kind());
        assertTrue(snapshot.symbols().stream().anyMatch(symbol ->
                symbol.symbol().kind() == SymbolKind.TYPE));
        assertTrue(snapshot.symbols().stream().allMatch(symbol ->
                "minos".equals(symbol.symbol().sourceProvider())));
        assertEquals(1, snapshot.relations().size());
        assertEquals(RelationKind.IMPLEMENTS, snapshot.relations().getFirst().relation().kind());
        assertEquals(1.0d, snapshot.relations().getFirst().relation().confidence());
        assertEquals("minos", snapshot.relations().getFirst().relation().sourceProvider());
    }

    @Test
    void preservesStructurallyDistinctSymbolsWithSameKindNameAndStartLine(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("Overloads.ts"), """
                export function run(): void {}
                export function run(value: string): void {}
                """);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = baseDocument(mapper, project);
        ArrayNode symbols = document.withArray("symbols");

        ObjectNode noArg = symbols.addObject();
        symbol(noArg, "run-no-arg", "src/Overloads.ts", "METHOD", "run", "demo.run", 1, 1);
        noArg.put("signature", "run()");

        ObjectNode withArg = symbols.addObject();
        symbol(withArg, "run-with-arg", "src/Overloads.ts", "METHOD", "run", "demo.run", 1, 2);
        withArg.put("signature", "run(value: string)");

        symbols.add(noArg.deepCopy());

        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter()
                .importPayload(project, mapper.writeValueAsString(document));

        assertEquals(2, snapshot.symbols().size(),
                "les deux faits structurellement distincts survivent et le doublon strict est éliminé");
        assertTrue(snapshot.symbols().stream().anyMatch(indexed -> "run()".equals(indexed.symbol().signature())));
        assertTrue(snapshot.symbols().stream().anyMatch(indexed ->
                "run(value: string)".equals(indexed.symbol().signature())));
    }

    @Test
    void rejectsForeignContractVersionAndProjectRoot(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path other = Files.createDirectories(temp.resolve("other"));
        ObjectMapper mapper = new ObjectMapper();
        MinosCodeIndexImporter importer = new MinosCodeIndexImporter();

        ObjectNode wrongVersion = baseDocument(mapper, project);
        wrongVersion.put("contractVersion", "2");
        String wrongVersionPayload = mapper.writeValueAsString(wrongVersion);
        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, wrongVersionPayload));

        ObjectNode wrongProducer = baseDocument(mapper, project);
        wrongProducer.put("producer", "OTHER");
        String wrongProducerPayload = mapper.writeValueAsString(wrongProducer);
        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, wrongProducerPayload));

        ObjectNode wrongRoot = baseDocument(mapper, other);
        String wrongRootPayload = mapper.writeValueAsString(wrongRoot);
        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, wrongRootPayload));
    }

    @Test
    void rejectsNullAndNonObjectJsonDocuments(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        MinosCodeIndexImporter importer = new MinosCodeIndexImporter();

        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, "null"));
        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, "[]"));
    }

    @Test
    void ignoresUnsafeUnknownAndUnsupportedPaths(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("GreetingPort.ts"), """
                export interface GreetingPort {
                    greet(): string;
                }
                """);
        Files.writeString(source.resolve("Greeter.ts"), """
                export class Greeter implements GreetingPort {
                    greet(): string {
                        return "hello";
                    }
                }
                """);
        ObjectMapper mapper = new ObjectMapper();

        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter()
                .importPayload(project, payload(mapper, project, true));

        assertEquals(2, snapshot.symbols().size());
        assertTrue(snapshot.symbols().stream().noneMatch(symbol ->
                "Outside".equals(symbol.symbol().name())
                        || "NormalizedTraversal".equals(symbol.symbol().name())
                        || "Absolute".equals(symbol.symbol().name())));
        assertEquals(1, snapshot.relations().size());
    }

    @Test
    void rejectsMalformedOrMissingDerivedConfidence(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("GreetingPort.ts"), "export interface GreetingPort {}\n");
        Files.writeString(source.resolve("Greeter.ts"), "export class Greeter {}\n");
        ObjectMapper mapper = new ObjectMapper();
        MinosCodeIndexImporter importer = new MinosCodeIndexImporter();

        ObjectNode malformed = baseDocument(mapper, project);
        ObjectNode malformedRelation = malformed.withArray("relations").addObject();
        relation(malformedRelation, "malformed", "src/Greeter.ts", "IMPLEMENTS", "Greeter", "GreetingPort");
        malformedRelation.put("confidence", "certain");
        String malformedPayload = mapper.writeValueAsString(malformed);
        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, malformedPayload));

        ObjectNode derived = baseDocument(mapper, project);
        ObjectNode derivedRelation = derived.withArray("relations").addObject();
        relation(derivedRelation, "derived", "src/Greeter.ts", "IMPLEMENTS", "Greeter", "GreetingPort");
        derivedRelation.put("nature", "DERIVED");
        String derivedPayload = mapper.writeValueAsString(derived);
        assertThrows(java.io.IOException.class, () -> importer.importPayload(project, derivedPayload));
    }

    private static String payload(ObjectMapper mapper, Path project, boolean includeUnsafe) throws Exception {
        ObjectNode document = baseDocument(mapper, project);
        ArrayNode symbols = document.withArray("symbols");
        symbol(symbols.addObject(), "port", "src/GreetingPort.ts", "INTERFACE", "GreetingPort", "GreetingPort", 1, 3);
        symbol(symbols.addObject(), "greeter", "src/Greeter.ts", "CLASS", "Greeter", "Greeter", 1, 5);
        symbol(symbols.addObject(), "generic", "src/Generic.ts", "TYPE", "Generic", "Generic", 1, 1);
        symbol(symbols.addObject(), "field", "src/Greeter.ts", "FIELD", "value", "Greeter.value", 2, 2);
        if (includeUnsafe) {
            symbol(symbols.addObject(), "traversal", "../outside.ts", "CLASS", "Outside", "Outside", 1, 1);
            symbol(symbols.addObject(), "normalized-traversal", "src/../src/GreetingPort.ts", "CLASS",
                    "NormalizedTraversal", "NormalizedTraversal", 1, 1);
            symbol(symbols.addObject(), "absolute", project.resolve("src/GreetingPort.ts").toAbsolutePath().toString(),
                    "CLASS", "Absolute", "Absolute", 1, 1);
        }

        ArrayNode relations = document.withArray("relations");
        relation(relations.addObject(), "implements", "src/Greeter.ts", "IMPLEMENTS", "Greeter", "GreetingPort");
        relation(relations.addObject(), "depends", "src/Greeter.ts", "DEPENDS_ON", "Greeter", "GreetingPort");
        return mapper.writeValueAsString(document);
    }

    private static ObjectNode baseDocument(ObjectMapper mapper, Path project) throws Exception {
        ObjectNode document = mapper.createObjectNode();
        document.put("contractVersion", "1");
        document.put("producer", "MINOS");
        ObjectNode projectNode = document.putObject("project");
        projectNode.put("id", "project-id");
        projectNode.put("name", "fixture");
        projectNode.put("rootPath", project.toRealPath().toString());
        projectNode.put("snapshotId", "snapshot-1");
        document.putArray("symbols");
        document.putArray("relations");
        document.putArray("limitations");
        return document;
    }

    private static void symbol(
            ObjectNode node,
            String id,
            String path,
            String kind,
            String name,
            String qualifiedName,
            int startLine,
            int endLine
    ) {
        node.put("id", id);
        node.put("symbolKey", "key-" + id);
        node.put("filePath", path);
        node.put("kind", kind);
        node.put("name", name);
        node.put("qualifiedName", qualifiedName);
        node.put("signature", name);
        node.put("language", "typescript");
        node.put("startLine", startLine);
        node.put("endLine", endLine);
        node.put("resolutionStatus", "RESOLVED");
        node.put("identityQuality", "CANONICAL");
        node.put("generated", false);
    }

    private static void relation(
            ObjectNode node,
            String id,
            String path,
            String kind,
            String source,
            String target
    ) {
        node.put("id", id);
        node.put("filePath", path);
        node.put("kind", kind);
        node.put("sourceId", source + "-id");
        node.put("sourceQualifiedName", source);
        node.put("targetId", target + "-id");
        node.put("targetQualifiedName", target);
        node.put("resolutionStatus", "RESOLVED");
        node.put("nature", "FACTUAL");
        node.putNull("confidence");
        node.putArray("evidence");
    }
}
