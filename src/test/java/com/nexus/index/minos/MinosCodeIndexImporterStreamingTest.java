package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.CodeIntelligenceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosCodeIndexImporterStreamingTest {

    @Test
    void parsesFactsEvenWhenMetadataAppearsAfterArrays(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src")).resolve("Type.ts");
        Files.writeString(source, "export class Type {}\n");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode();
        ArrayNode symbols = document.putArray("symbols");
        ObjectNode symbol = symbols.addObject();
        symbol.put("filePath", "src/Type.ts");
        symbol.put("kind", "CLASS");
        symbol.put("name", "Type");
        symbol.put("qualifiedName", "Type");
        symbol.put("signature", "Type");
        symbol.put("startLine", 1);
        symbol.put("endLine", 1);
        symbol.put("resolutionStatus", "RESOLVED");
        document.putArray("relations");
        document.put("producer", "MINOS");
        ObjectNode projectNode = document.putObject("project");
        projectNode.put("rootPath", project.toRealPath().toString());
        document.put("contractVersion", "1");

        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter()
                .importPayload(project, mapper.writeValueAsString(document));

        assertEquals(1, snapshot.symbols().size());
        assertEquals("Type", snapshot.symbols().getFirst().symbol().name());
    }

    @Test
    void boundedStdinReaderPreservesUtf8Payload() throws Exception {
        String payload = "{\"contractVersion\":\"1\",\"note\":\"émoji 😀\"}";

        String read = MinosCodeIndexImporter.readPayload(new ByteArrayInputStream(
                payload.getBytes(StandardCharsets.UTF_8)));

        assertEquals(payload, read);
    }

    @Test
    void rejectsTrailingJsonAfterTheRootDocument(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String root = project.toRealPath().toString().replace("\\", "\\\\");
        String payload = "{\"contractVersion\":\"1\",\"producer\":\"MINOS\","
                + "\"project\":{\"rootPath\":\"" + root + "\"},\"symbols\":[],\"relations\":[]} {}";

        assertThrows(
                java.io.IOException.class,
                () -> new MinosCodeIndexImporter().importPayload(project, payload));
    }
}
