package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosCodeIndexImporterTest {

    @Test
    void integrationIsDisabledByDefaultAndRequiresExplicitJava24Command(@TempDir Path temp) throws Exception {
        MinosCodeIndexImporter disabled = MinosCodeIndexImporter.fromEnvironment(Map.of());
        assertFalse(disabled.enabled());
        assertTrue(disabled.importIndex(Files.createDirectories(temp.resolve("project"))).isEmpty());

        Path jar = Files.createFile(temp.resolve("minos.jar"));
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> MinosCodeIndexImporter.fromEnvironment(Map.of(
                        MinosCodeIndexImporter.JAR_ENVIRONMENT_VARIABLE, jar.toString())));
        assertTrue(exception.getMessage().contains(MinosCodeIndexImporter.JAVA_ENVIRONMENT_VARIABLE));
    }

    @Test
    void mapsVersionedMinosFactsConservatively(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path jar = Files.createFile(temp.resolve("minos.jar"));
        ObjectMapper mapper = new ObjectMapper();
        String payload = payload(mapper, project);
        MinosCodeIndexImporter.Configuration configuration = new MinosCodeIndexImporter.Configuration(
                jar,
                null,
                "java24",
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
        Path jar = Files.createFile(temp.resolve("minos.jar"));
        ObjectMapper mapper = new ObjectMapper();
        MinosCodeIndexImporter.Configuration configuration = new MinosCodeIndexImporter.Configuration(
                jar,
                null,
                "java24",
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
    void executesConfiguredExporterAsLocalProcess(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path fakeJar = createFakeExporterJar(temp.resolve("fake-minos.jar"));
        String javaCommand = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java").toString();
        MinosCodeIndexImporter importer = new MinosCodeIndexImporter(
                new MinosCodeIndexImporter.Configuration(
                        fakeJar,
                        temp.resolve("minos-home"),
                        javaCommand,
                        Duration.ofSeconds(10)));

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

    private static Path createFakeExporterJar(Path jar) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeMinosExportMain.class.getName());
        String resource = FakeMinosExportMain.class.getName().replace('.', '/') + ".class";
        try (InputStream input = FakeMinosExportMain.class.getClassLoader().getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (input == null) {
                throw new IllegalStateException("missing test class resource: " + resource);
            }
            output.putNextEntry(new JarEntry(resource));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
