package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosCodeIndexImporterTest {

    @Test
    void integrationIsOptInThroughExplicitActivation(@TempDir Path temp) throws Exception {
        NexusPaths paths = new NexusPaths(Files.createDirectories(temp.resolve("nexus-home")));
        MinosCodeIndexImporter disabled = MinosCodeIndexImporter.fromPaths(paths, false);
        assertFalse(disabled.enabled());
        assertTrue(disabled.importIndex(Files.createDirectories(temp.resolve("project"))).isEmpty());

        MinosCodeIndexImporter enabled = MinosCodeIndexImporter.fromPaths(paths, true);
        assertTrue(enabled.enabled());
    }

    @Test
    void mapsVersionedMinosFactsConservatively(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("GreetingPort.ts"), "export interface GreetingPort {}\n");
        Files.writeString(source.resolve("Greeter.ts"), "export class Greeter {}\n");
        NexusPaths paths = new NexusPaths(Files.createDirectories(temp.resolve("nexus-home")));
        ObjectMapper mapper = new ObjectMapper();
        String payload = payload(mapper, project);
        MinosCodeIndexImporter.Configuration configuration = new MinosCodeIndexImporter.Configuration(
                paths.home(),
                Duration.ofSeconds(10));
        MinosCodeIndexImporter importer = new MinosCodeIndexImporter(
                configuration,
                ignored -> payload,
                mapper);

        CodeIntelligenceSnapshot snapshot = importer.importIndex(project).orElseThrow();

        assertEquals("minos", snapshot.sourceProvider());
        assertEquals(2, snapshot.symbols().size());
        assertEquals(SymbolKind.INTERFACE, snapshot.symbols().getFirst().symbol().kind());
        assertTrue(snapshot.symbols().stream().allMatch(symbol ->
                "minos".equals(symbol.symbol().sourceProvider())));
        assertEquals(1, snapshot.relations().size());
        assertEquals(RelationKind.IMPLEMENTS, snapshot.relations().getFirst().relation().kind());
        assertEquals(1.0d, snapshot.relations().getFirst().relation().confidence());
        assertEquals("minos", snapshot.relations().getFirst().relation().sourceProvider());
    }

    @Test
    void rejectsForeignContractVersionAndProjectRoot(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path other = Files.createDirectories(temp.resolve("other"));
        NexusPaths paths = new NexusPaths(Files.createDirectories(temp.resolve("nexus-home")));
        ObjectMapper mapper = new ObjectMapper();
        MinosCodeIndexImporter.Configuration configuration = new MinosCodeIndexImporter.Configuration(
                paths.home(),
                Duration.ofSeconds(10));

        ObjectNode wrongVersion = baseDocument(mapper, project);
        wrongVersion.put("contractVersion", "2");
        MinosCodeIndexImporter versionImporter = new MinosCodeIndexImporter(
                configuration,
                ignored -> mapper.writeValueAsString(wrongVersion),
                mapper);
        assertThrows(java.io.IOException.class, () -> versionImporter.importIndex(project));

        ObjectNode wrongRoot = baseDocument(mapper, other);
        MinosCodeIndexImporter rootImporter = new MinosCodeIndexImporter(
                configuration,
                ignored -> mapper.writeValueAsString(wrongRoot),
                mapper);
        assertThrows(java.io.IOException.class, () -> rootImporter.importIndex(project));
    }

    @Test
    void executesFixedBridgeCommandAndSendsProjectRootOnStdin(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        NexusPaths paths = new NexusPaths(Files.createDirectories(temp.resolve("nexus-home")));
        Files.createDirectories(paths.minosIntegrationDirectory());
        createFakeBridgeJar(paths.minosIntegrationJar(), temp.resolve("fake-bridge"));

        MinosCodeIndexImporter importer = MinosCodeIndexImporter.fromPaths(paths, true);
        CodeIntelligenceSnapshot snapshot = importer.importIndex(project).orElseThrow();

        assertEquals("minos", snapshot.sourceProvider());
        assertTrue(snapshot.symbols().isEmpty());
        assertTrue(snapshot.relations().isEmpty());
    }

    private static String payload(ObjectMapper mapper, Path project) throws Exception {
        ObjectNode document = baseDocument(mapper, project);
        ArrayNode symbols = document.withArray("symbols");
        symbol(symbols.addObject(), "port", "src/GreetingPort.ts", "INTERFACE", "GreetingPort", "GreetingPort", 1, 3);
        symbol(symbols.addObject(), "greeter", "src/Greeter.ts", "CLASS", "Greeter", "Greeter", 1, 5);
        symbol(symbols.addObject(), "field", "src/Greeter.ts", "FIELD", "value", "Greeter.value", 2, 2);
        symbol(symbols.addObject(), "traversal", "../outside.ts", "CLASS", "Outside", "Outside", 1, 1);
        symbol(symbols.addObject(), "absolute", project.resolve("src/GreetingPort.ts").toAbsolutePath().toString(),
                "CLASS", "Absolute", "Absolute", 1, 1);

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

    private static void createFakeBridgeJar(Path jar, Path work) throws Exception {
        Path sourceRoot = Files.createDirectories(work.resolve("src/com/minos/integration/nexus"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        Path source = sourceRoot.resolve("NexusExportBridgeMain.java");
        Files.writeString(source, """
                package com.minos.integration.nexus;

                import java.io.BufferedReader;
                import java.io.InputStreamReader;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Path;

                public final class NexusExportBridgeMain {
                    private NexusExportBridgeMain() {
                    }

                    public static void main(String[] args) throws Exception {
                        String root = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                                .readLine();
                        String canonical = Path.of(root).toRealPath().toString();
                        char backslash = 92;
                        char quote = 34;
                        String escaped = canonical
                                .replace(Character.toString(backslash),
                                        Character.toString(backslash) + Character.toString(backslash))
                                .replace(Character.toString(quote),
                                        Character.toString(backslash) + Character.toString(quote));
                        String q = Character.toString(quote);
                        System.out.print("{" + q + "contractVersion" + q + ":" + q + "1" + q + ","
                                + q + "producer" + q + ":" + q + "MINOS" + q + ","
                                + q + "project" + q + ":{"
                                + q + "id" + q + ":" + q + "fake" + q + ","
                                + q + "name" + q + ":" + q + "fake" + q + ","
                                + q + "rootPath" + q + ":" + q + escaped + q + ","
                                + q + "snapshotId" + q + ":" + q + "fake-snapshot" + q + "},"
                                + q + "symbols" + q + ":[],"
                                + q + "relations" + q + ":[],"
                                + q + "limitations" + q + ":[]}");
                    }
                }
                """);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "tests require a JDK compiler");
        assertEquals(0, compiler.run(null, null, null,
                "-encoding", "UTF-8", "-d", classes.toString(), source.toString()));

        Path classFile = classes.resolve("com/minos/integration/nexus/NexusExportBridgeMain.class");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("com/minos/integration/nexus/NexusExportBridgeMain.class"));
            Files.copy(classFile, output);
            output.closeEntry();
        }
    }
}
