package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.CodeIntelligenceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinosRelationIdentityTest {

    @Test
    void keepsDistinctFileProvenanceAndMaximumConfidence(@TempDir Path temporaryDirectory) throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = Files.createDirectories(project.resolve("src"));
        Files.writeString(source.resolve("One.ts"), "export const one = 1;\n");
        Files.writeString(source.resolve("Two.ts"), "export const two = 2;\n");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = mapper.createObjectNode();
        document.put("contractVersion", "1");
        document.put("producer", "MINOS");
        ObjectNode projectNode = document.putObject("project");
        projectNode.put("id", "project-id");
        projectNode.put("name", "fixture");
        projectNode.put("rootPath", project.toRealPath().toString());
        projectNode.put("snapshotId", "snapshot-1");
        document.putArray("symbols");
        var relations = document.putArray("relations");
        document.putArray("limitations");

        addRelation(relations.addObject(), "one-low", "src/One.ts", 0.40d);
        addRelation(relations.addObject(), "two", "src/Two.ts", 0.80d);
        addRelation(relations.addObject(), "one-high", "src/One.ts", 0.90d);
        addRelation(relations.addObject(), "one-high-duplicate", "src/One.ts", 0.90d);

        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter()
                .importPayload(project, mapper.writeValueAsString(document));

        assertEquals(2, snapshot.relations().size());
        assertEquals(0.90d, confidenceFor(snapshot, "src/One.ts"));
        assertEquals(0.80d, confidenceFor(snapshot, "src/Two.ts"));
    }

    private static void addRelation(ObjectNode relation, String id, String path, double confidence) {
        relation.put("id", id);
        relation.put("filePath", path);
        relation.put("kind", "REFERENCES");
        relation.put("sourceId", "source-id");
        relation.put("sourceQualifiedName", "demo.Source");
        relation.put("targetId", "target-id");
        relation.put("targetQualifiedName", "demo.Target");
        relation.put("resolutionStatus", "RESOLVED");
        relation.put("nature", "DERIVED");
        relation.put("confidence", confidence);
        relation.putArray("evidence");
    }

    private static double confidenceFor(CodeIntelligenceSnapshot snapshot, String relativePath) {
        return snapshot.relations().stream()
                .filter(relation -> relativePath.equals(relation.relativePath()))
                .findFirst()
                .orElseThrow()
                .relation()
                .confidence();
    }
}
