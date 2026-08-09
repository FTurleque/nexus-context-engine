package com.nexus.index.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScipSymbolRangeValidationTest {

    private static final String SYMBOL = "scip-java maven demo app 1.0 demo/Fixture#";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsEndBeforeStart() throws Exception {
        Path root = project("class Fixture {}\n");
        writeIndex(root, 2, 1);

        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));
    }

    @Test
    void rejectsRangeBeyondCanonicalFileAndEmptyFile() throws Exception {
        Path root = project("class Fixture {}\n");
        writeIndex(root, 0, 99);
        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));

        Path emptyRoot = Files.createDirectories(temporaryDirectory.resolve("empty"));
        Files.writeString(emptyRoot.resolve("Fixture.java"), "");
        writeIndex(emptyRoot, 0, 0);
        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(emptyRoot));
    }

    @Test
    void acceptsDefinitionOnLastLine() throws Exception {
        Path root = project("one\ntwo\nclass Fixture {}");
        writeIndex(root, 2, 2);

        var snapshot = new ScipCodeIndexImporter().importIndex(root).orElseThrow();

        assertEquals(1, snapshot.symbols().size());
        assertEquals(3, snapshot.symbols().getFirst().symbol().startLine());
        assertEquals(3, snapshot.symbols().getFirst().symbol().endLine());
    }

    private Path project(String content) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(root.resolve("Fixture.java"), content);
        return root;
    }

    private static void writeIndex(Path root, int startLine, int endLine) throws IOException {
        byte[] occurrence = message(
                varintField(1, startLine),
                varintField(1, 0),
                varintField(1, endLine),
                varintField(1, 1),
                stringField(2, SYMBOL),
                varintField(3, 1));
        byte[] symbolInformation = message(
                stringField(1, SYMBOL),
                varintField(5, 7),
                stringField(6, "Fixture"));
        byte[] document = message(
                stringField(1, "Fixture.java"),
                messageField(2, occurrence),
                messageField(3, symbolInformation));
        Files.write(root.resolve("index.scip"), message(messageField(2, document)));
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

    private static byte[] varintField(int fieldNumber, long value) {
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
