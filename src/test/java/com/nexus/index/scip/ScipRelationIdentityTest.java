package com.nexus.index.scip;

import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScipRelationIdentityTest {

    @Test
    void keepsSameLogicalRelationFromDistinctDocuments(@TempDir Path temporaryDirectory) throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        String source = "scip-java maven demo app 1.0 demo/Source#";
        String target = "scip-java maven demo api 1.0 demo/Target#";

        byte[] relationship = message(
                stringField(1, target),
                varintField(2, 1));
        byte[] symbolInformation = message(
                stringField(1, source),
                messageField(4, relationship),
                varintField(5, 7),
                stringField(6, "Source"));
        byte[] firstDocument = message(
                stringField(1, "src/One.java"),
                messageField(3, symbolInformation));
        byte[] secondDocument = message(
                stringField(1, "src/Two.java"),
                messageField(3, symbolInformation));
        Files.write(
                projectRoot.resolve("index.scip"),
                message(messageField(2, firstDocument), messageField(2, secondDocument)));

        CodeIntelligenceSnapshot snapshot = new ScipCodeIndexImporter()
                .importIndex(projectRoot)
                .orElseThrow();

        Set<String> paths = snapshot.relations().stream()
                .filter(relation -> relation.relation().kind() == RelationKind.REFERENCES)
                .filter(relation -> source.equals(relation.relation().source()))
                .filter(relation -> target.equals(relation.relation().target()))
                .map(relation -> relation.relativePath())
                .collect(Collectors.toSet());

        assertEquals(Set.of("src/One.java", "src/Two.java"), paths);
    }

    private static byte[] message(byte[]... fields) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            output.write(field);
        }
        return output.toByteArray();
    }

    private static byte[] stringField(int fieldNumber, String value) throws IOException {
        return messageField(fieldNumber, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] messageField(int fieldNumber, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) fieldNumber << 3) | 2L);
        writeVarint(output, payload.length);
        output.write(payload);
        return output.toByteArray();
    }

    private static byte[] varintField(int fieldNumber, long value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, (long) fieldNumber << 3);
        writeVarint(output, value);
        return output.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0L) {
            output.write((int) ((remaining & 0x7fL) | 0x80L));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }
}
