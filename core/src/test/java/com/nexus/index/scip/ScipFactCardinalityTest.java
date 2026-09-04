package com.nexus.index.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipFactCardinalityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsRelationsBeyondGlobalCardinalityLimit() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("relations"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] document = message(
                stringField(1, relativePath),
                messageField(2, message(stringField(2, "symbol-1"))),
                messageField(2, message(stringField(2, "symbol-2"))),
                messageField(2, message(stringField(2, "symbol-3"))));
        Files.write(root.resolve("index.scip"), messageField(2, document));

        IOException failure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1_000_000, 100_000, 10, 2, 10)
                        .importIndex(root));

        assertTrue(failure.getMessage().contains("2 faits relation"), failure.getMessage());
    }

    @Test
    void rejectsSymbolsBeyondGlobalCardinalityLimit() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("symbols"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] firstSymbol = "scip-java maven demo app 1.0 demo/First#".getBytes();
        byte[] secondSymbol = "scip-java maven demo app 1.0 demo/Second#".getBytes();
        byte[] firstOccurrence = message(
                packedInt32Field(1, 0, 0, 1),
                rawStringField(2, firstSymbol),
                varintField(3, 1));
        byte[] secondOccurrence = message(
                packedInt32Field(1, 0, 0, 1),
                rawStringField(2, secondSymbol),
                varintField(3, 1));
        byte[] firstInfo = message(
                rawStringField(1, firstSymbol),
                varintField(5, 7),
                stringField(6, "First"));
        byte[] secondInfo = message(
                rawStringField(1, secondSymbol),
                varintField(5, 7),
                stringField(6, "Second"));
        byte[] document = message(
                stringField(1, relativePath),
                messageField(2, firstOccurrence),
                messageField(2, secondOccurrence),
                messageField(3, firstInfo),
                messageField(3, secondInfo));
        Files.write(root.resolve("index.scip"), messageField(2, document));

        IOException failure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1_000_000, 100_000, 1, 10, 10)
                        .importIndex(root));

        assertTrue(failure.getMessage().contains("1 faits symbole"), failure.getMessage());
    }

    @Test
    void rejectsCombinedFactsBeyondTotalCardinalityLimit() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("total"));
        String relativePath = "src/App.java";
        writeSource(root, relativePath);

        byte[] document = message(
                stringField(1, relativePath),
                messageField(2, message(stringField(2, "symbol-1"))),
                messageField(2, message(stringField(2, "symbol-2"))),
                messageField(2, message(stringField(2, "symbol-3"))));
        Files.write(root.resolve("index.scip"), messageField(2, document));

        IOException failure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1_000_000, 100_000, 10, 10, 2)
                        .importIndex(root));

        assertTrue(failure.getMessage().contains("2 faits totaux"), failure.getMessage());
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
        return rawStringField(fieldNumber, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] rawStringField(int fieldNumber, byte[] value) throws IOException {
        return messageField(fieldNumber, value);
    }

    private static byte[] messageField(int fieldNumber, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, ((long) fieldNumber << 3) | 2L);
        writeVarint(output, payload.length);
        output.write(payload);
        return output.toByteArray();
    }

    private static byte[] varintField(int fieldNumber, long value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarint(output, (long) fieldNumber << 3);
        writeVarint(output, value);
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
