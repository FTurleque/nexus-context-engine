package com.nexus.index.scip;

import com.nexus.index.CodeIntelligenceSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScipProjectPathGuardTest {

    private static final String SYMBOL = "scip-java maven demo app 1.0 demo/Fixture#";

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsCanonicalNestedSource() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("valid-project"));
        Path source = root.resolve("src/Fixture.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "one\ntwo\nclass Fixture {}");
        writeIndex(root, "src/Fixture.java", 2, 2);

        CodeIntelligenceSnapshot snapshot = new ScipCodeIndexImporter().importIndex(root).orElseThrow();

        assertEquals(1, snapshot.symbols().size());
        assertEquals("src/Fixture.java", snapshot.symbols().getFirst().relativePath());
        assertEquals(3, snapshot.symbols().getFirst().symbol().startLine());
    }

    @Test
    void rejectsSourceTraversalOutsideRepository() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("traversal-project"));
        Files.writeString(temporaryDirectory.resolve("outside.java"), "outside secret");
        writeIndex(root, "../outside.java", 0, 0);

        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));
    }

    @Test
    void rejectsFinalSourceSymlink() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("final-link-project"));
        Path sourceDirectory = Files.createDirectories(root.resolve("src"));
        Path outside = temporaryDirectory.resolve("outside-final.java");
        Files.writeString(outside, "outside secret");
        Path source = sourceDirectory.resolve("Fixture.java");
        assumeSymlink(source, outside);
        writeIndex(root, "src/Fixture.java", 0, 0);

        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));
    }

    @Test
    void rejectsAncestorDirectorySymlink() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("ancestor-link-project"));
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-dir"));
        Files.writeString(outsideDirectory.resolve("Fixture.java"), "outside secret");
        assumeSymlink(root.resolve("src"), outsideDirectory);
        writeIndex(root, "src/Fixture.java", 0, 0);

        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));
    }

    @Test
    void rejectsCanonicalSourceDeletedAfterIndexCreation() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("deleted-project"));
        Path source = root.resolve("Fixture.java");
        Files.writeString(source, "class Fixture {}");
        writeIndex(root, "Fixture.java", 0, 0);
        Files.delete(source);

        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));
    }

    @Test
    void rejectsSymlinkedScipIndexInsteadOfTreatingItAsAbsent() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("index-link-project"));
        Files.writeString(root.resolve("Fixture.java"), "class Fixture {}");
        Path externalIndexRoot = Files.createDirectory(temporaryDirectory.resolve("external-index"));
        writeIndex(externalIndexRoot, "Fixture.java", 0, 0);
        assumeSymlink(root.resolve("index.scip"), externalIndexRoot.resolve("index.scip"));

        assertThrows(IOException.class, () -> new ScipCodeIndexImporter().importIndex(root));
    }

    private static void writeIndex(Path root, String relativePath, int startLine, int endLine) throws IOException {
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
                stringField(1, relativePath),
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

    private static void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(
                    false,
                    "Symbolic links are not supported in this test environment: " + exception);
        }
    }
}
