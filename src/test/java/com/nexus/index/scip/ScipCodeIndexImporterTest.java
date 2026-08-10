package com.nexus.index.scip;

import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipCodeIndexImporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsEmptyWhenNoScipIndexIsAvailable() throws Exception {
        ScipCodeIndexImporter importer = new ScipCodeIndexImporter();

        assertTrue(importer.importIndex(temporaryDirectory).isEmpty());
    }

    @Test
    void importsDefinitionsReferencesAndRelationshipsFromScip() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        String methodSymbol = "scip-java maven demo app 1.0 demo/App#run().";
        String contractSymbol = "scip-java maven demo api 1.0 demo/Contract#run().";

        byte[] typedRange = message(
                varintField(1, 2),
                varintField(2, 4),
                varintField(3, 7));
        byte[] legacyRange = packedInt32Field(1, 9, 1, 5);
        byte[] definitionOccurrence = message(
                legacyRange,
                stringField(2, methodSymbol),
                varintField(3, 1),
                messageField(8, typedRange));
        byte[] referenceOccurrence = message(
                stringField(2, methodSymbol),
                messageField(8, message(
                        varintField(1, 5),
                        varintField(2, 8),
                        varintField(3, 11))));

        byte[] relationship = message(
                stringField(1, contractSymbol),
                varintField(2, 1),
                varintField(3, 1));
        byte[] signature = message(stringField(5, "void run()"));
        byte[] symbolInformation = message(
                stringField(1, methodSymbol),
                messageField(4, relationship),
                varintField(5, 26),
                stringField(6, "run"),
                messageField(7, signature));
        byte[] document = message(
                stringField(1, "src/main/java/demo/App.java"),
                messageField(2, definitionOccurrence),
                messageField(2, referenceOccurrence),
                messageField(3, symbolInformation),
                stringField(4, "java"));
        Files.write(projectRoot.resolve("index.scip"), messageField(2, document));

        CodeIntelligenceSnapshot snapshot = new ScipCodeIndexImporter()
                .importIndex(projectRoot)
                .orElseThrow();

        assertEquals(ScipCodeIndexImporter.SOURCE_PROVIDER, snapshot.sourceProvider());
        assertEquals(1, snapshot.symbols().size());
        assertEquals("src/main/java/demo/App.java", snapshot.symbols().getFirst().relativePath());
        assertEquals(SymbolKind.METHOD, snapshot.symbols().getFirst().symbol().kind());
        assertEquals("run", snapshot.symbols().getFirst().symbol().name());
        assertEquals("void run()", snapshot.symbols().getFirst().symbol().signature());
        assertEquals(3, snapshot.symbols().getFirst().symbol().startLine());
        assertEquals(3, snapshot.symbols().getFirst().symbol().endLine());
        assertEquals(ScipCodeIndexImporter.SOURCE_PROVIDER, snapshot.symbols().getFirst().symbol().sourceProvider());

        assertTrue(snapshot.relations().stream().anyMatch(indexed ->
                indexed.relation().kind() == RelationKind.IMPLEMENTS
                        && indexed.relation().source().equals(methodSymbol)
                        && indexed.relation().target().equals(contractSymbol)));
        assertTrue(snapshot.relations().stream().anyMatch(indexed ->
                indexed.relation().kind() == RelationKind.REFERENCES
                        && indexed.relation().source().equals("src/main/java/demo/App.java")
                        && indexed.relation().target().equals(methodSymbol)));
        assertTrue(snapshot.relations().stream().allMatch(indexed ->
                indexed.relation().sourceProvider().equals(ScipCodeIndexImporter.SOURCE_PROVIDER)));
    }

    @Test
    void fallsBackToLegacyPackedRangeWhenTypedRangeIsMissing() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("legacy-project"));
        String typeSymbol = "scip-java maven demo app 1.0 demo/Legacy#";
        byte[] definitionOccurrence = message(
                packedInt32Field(1, 4, 0, 6, 1),
                stringField(2, typeSymbol),
                varintField(3, 1));
        byte[] symbolInformation = message(
                stringField(1, typeSymbol),
                varintField(5, 7),
                stringField(6, "Legacy"));
        byte[] document = message(
                stringField(1, "src/main/java/demo/Legacy.java"),
                messageField(2, definitionOccurrence),
                messageField(3, symbolInformation));
        Files.write(projectRoot.resolve("index.scip"), messageField(2, document));

        CodeIntelligenceSnapshot snapshot = new ScipCodeIndexImporter()
                .importIndex(projectRoot)
                .orElseThrow();

        assertEquals(1, snapshot.symbols().size());
        assertEquals(SymbolKind.CLASS, snapshot.symbols().getFirst().symbol().kind());
        assertEquals(5, snapshot.symbols().getFirst().symbol().startLine());
        assertEquals(7, snapshot.symbols().getFirst().symbol().endLine());
    }

    @Test
    void ignoresUnsupportedSymbolKindsWithoutDroppingTheirReferences() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("unsupported-kind-project"));
        String fieldSymbol = "scip-java maven demo app 1.0 demo/App#value.";
        String relativePath = "src/main/java/demo/App.java";
        byte[] definitionOccurrence = message(
                packedInt32Field(1, 2, 4, 2, 9),
                stringField(2, fieldSymbol),
                varintField(3, 1));
        byte[] referenceOccurrence = message(
                packedInt32Field(1, 5, 8, 5, 13),
                stringField(2, fieldSymbol));
        byte[] fieldInformation = message(
                stringField(1, fieldSymbol),
                varintField(5, 15),
                stringField(6, "value"));
        byte[] document = message(
                stringField(1, relativePath),
                messageField(2, definitionOccurrence),
                messageField(2, referenceOccurrence),
                messageField(3, fieldInformation));
        Files.write(projectRoot.resolve("index.scip"), messageField(2, document));

        CodeIntelligenceSnapshot snapshot = new ScipCodeIndexImporter()
                .importIndex(projectRoot)
                .orElseThrow();

        assertTrue(snapshot.symbols().isEmpty());
        assertTrue(snapshot.relations().stream().anyMatch(indexed ->
                indexed.relation().kind() == RelationKind.REFERENCES
                        && indexed.relation().source().equals(relativePath)
                        && indexed.relation().target().equals(fieldSymbol)));
    }

    @Test
    void rejectsOversizedIndexAndLengthDelimitedDocumentBeforeAllocation() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("bounded-project"));
        Path index = projectRoot.resolve("index.scip");
        Files.write(index, new byte[32]);
        assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 16, 16).importIndex(projectRoot));

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        writeVarint(encoded, ((long) 2 << 3) | 2L);
        writeVarint(encoded, 17L);
        Files.write(index, encoded.toByteArray());
        assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1024, 16).importIndex(projectRoot));
    }

    @Test
    void unknownLengthDelimitedFieldCannotBypassTotalBudgetThroughSkip() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("unknown-skip-project"));
        Path index = projectRoot.resolve("index.scip");
        byte[] encoded = messageField(99, new byte[20_000]);
        Files.write(index, encoded);

        CodeIntelligenceSnapshot exactBoundary = new ScipCodeIndexImporter(
                "index.scip",
                encoded.length,
                64)
                .importIndex(projectRoot)
                .orElseThrow();
        assertTrue(exactBoundary.symbols().isEmpty());
        assertTrue(exactBoundary.relations().isEmpty());

        IOException failure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", encoded.length - 1L, 64)
                        .importIndex(projectRoot));
        assertTrue(failure.getMessage().contains("maximum " + (encoded.length - 1L) + " octets"),
                failure.getMessage());
    }

    @Test
    void truncatedUnknownLengthDelimitedFieldFailsWhileSkipping() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("truncated-skip-project"));
        Path index = projectRoot.resolve("index.scip");
        byte[] complete = messageField(99, new byte[20_000]);
        Files.write(index, Arrays.copyOf(complete, 10_000));

        IOException failure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 30_000L, 64).importIndex(projectRoot));
        assertTrue(failure.getMessage().contains("tronqué"), failure.getMessage());
    }

    @Test
    void rejectsLengthOverflowBeforeLongToIntConversionOrAllocation() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("overflow-project"));
        Path index = projectRoot.resolve("index.scip");
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        writeVarint(encoded, ((long) 2 << 3) | 2L);
        writeVarint(encoded, (long) Integer.MAX_VALUE + 1L);
        Files.write(index, encoded.toByteArray());

        IOException failure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1024, Integer.MAX_VALUE).importIndex(projectRoot));
        assertTrue(failure.getMessage().contains("trop volumineux")
                        || failure.getMessage().contains("hors limites"),
                failure.getMessage());
    }

    @Test
    void rejectsTruncatedAndMalformedLengthDelimitedPayloads() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("malformed-project"));
        Path index = projectRoot.resolve("index.scip");

        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        writeVarint(truncated, ((long) 2 << 3) | 2L);
        writeVarint(truncated, 5L);
        truncated.write(new byte[]{1, 2});
        Files.write(index, truncated.toByteArray());
        IOException truncatedFailure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1024, 32).importIndex(projectRoot));
        assertTrue(truncatedFailure.getMessage().contains("tronqué"), truncatedFailure.getMessage());

        ByteArrayOutputStream malformed = new ByteArrayOutputStream();
        for (int i = 0; i < 11; i++) {
            malformed.write(0x80);
        }
        Files.write(index, malformed.toByteArray());
        IOException malformedFailure = assertThrows(IOException.class, () ->
                new ScipCodeIndexImporter("index.scip", 1024, 32).importIndex(projectRoot));
        assertTrue(malformedFailure.getMessage().contains("Varint SCIP invalide"), malformedFailure.getMessage());
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
