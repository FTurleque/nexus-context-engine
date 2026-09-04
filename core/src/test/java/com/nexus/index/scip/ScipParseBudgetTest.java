package com.nexus.index.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipParseBudgetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOccurrenceBoundaryAndRejectsNPlusOneBeforeMaterialization() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("occurrences"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] twoOccurrences = documentWithOccurrences(relativePath, 2);
        Files.write(root.resolve("index.scip"), messageField(2, twoOccurrences));

        var exact = importer(new ScipCodeIndexImporter.ParseLimits(10, 2, 10, 10))
                .importIndex(root)
                .orElseThrow();
        assertEquals(2, exact.relations().size());

        byte[] threeOccurrences = documentWithOccurrences(relativePath, 3);
        Files.write(root.resolve("index.scip"), messageField(2, threeOccurrences));

        IOException failure = assertThrows(IOException.class, () ->
                importer(new ScipCodeIndexImporter.ParseLimits(10, 2, 10, 10)).importIndex(root));
        assertTrue(failure.getMessage().contains("2 occurrences SCIP"), failure.getMessage());
    }

    @Test
    void rejectsRelationshipNPlusOneBeforeNestedMessageAllocation() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("relationships"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] symbolInfo = message(
                stringField(1, "source"),
                messageField(4, message(stringField(1, "target-1"))),
                messageField(4, message(stringField(1, "target-2"))),
                messageField(4, message(stringField(1, "target-3"))));
        byte[] document = message(
                stringField(1, relativePath),
                messageField(3, symbolInfo));
        Files.write(root.resolve("index.scip"), messageField(2, document));

        IOException failure = assertThrows(IOException.class, () ->
                importer(new ScipCodeIndexImporter.ParseLimits(10, 10, 10, 2)).importIndex(root));
        assertTrue(failure.getMessage().contains("2 relationships SCIP"), failure.getMessage());
    }

    @Test
    void rejectsDocumentNPlusOneBeforeTopLevelPayloadAllocation() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("documents"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] document = message(stringField(1, relativePath));
        Files.write(root.resolve("index.scip"), message(
                messageField(2, document),
                messageField(2, document),
                messageField(2, document)));

        IOException failure = assertThrows(IOException.class, () ->
                importer(new ScipCodeIndexImporter.ParseLimits(2, 10, 10, 10)).importIndex(root));
        assertTrue(failure.getMessage().contains("2 documents SCIP"), failure.getMessage());
    }

    @Test
    void rejectsOversizedLegacyPackedRange() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("legacy-range"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] occurrence = message(
                packedInt32Field(1, 0, 1, 0, 2, 3),
                stringField(2, "symbol"));
        byte[] document = message(
                stringField(1, relativePath),
                messageField(2, occurrence));
        Files.write(root.resolve("index.scip"), messageField(2, document));

        IOException failure = assertThrows(IOException.class, () ->
                importer(new ScipCodeIndexImporter.ParseLimits(10, 10, 10, 10)).importIndex(root));
        assertTrue(failure.getMessage().contains("Plage legacy SCIP"), failure.getMessage());
    }

    private static ScipCodeIndexImporter importer(ScipCodeIndexImporter.ParseLimits parseLimits) {
        return new ScipCodeIndexImporter(
                "index.scip",
                1_000_000,
                100_000,
                100,
                100,
                200,
                parseLimits);
    }

    private static byte[] documentWithOccurrences(String relativePath, int count) throws IOException {
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        document.write(stringField(1, relativePath));
        for (int index = 0; index < count; index++) {
            document.write(messageField(2, message(stringField(2, "symbol-" + index))));
        }
        return document.toByteArray();
    }

    private static void writeSource(Path root, String relativePath) throws IOException {
        Path source = root.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n");
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

    private static byte[] packedInt32Field(int fieldNumber, int... values) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int value : values) {
            writeVarint(payload, value);
        }
        return messageField(fieldNumber, payload.toByteArray());
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
