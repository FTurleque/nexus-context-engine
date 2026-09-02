package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.index.CodeIntelligenceSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosProjectPathGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsCanonicalAllowlistedSource() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("valid-project"));
        Path source = root.resolve("src/Valid.ts");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "one\ntwo\nthree");

        CodeIntelligenceSnapshot snapshot = importSymbol(root, Set.of("src/Valid.ts"), "src/Valid.ts", 3, 3);

        assertEquals(1, snapshot.symbols().size());
        assertEquals(3, snapshot.symbols().getFirst().symbol().endLine());
    }

    @Test
    void rejectsFinalFileReplacedBySymlinkAfterAllowlistSelection() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("final-link-project"));
        Path source = root.resolve("src/Selected.ts");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "safe");
        Set<String> canonicalAllowlist = Set.of("src/Selected.ts");

        Path outside = temporaryDirectory.resolve("outside-final.ts");
        Files.writeString(outside, "outside secret");
        Files.delete(source);
        assumeSymlink(source, outside);

        assertThrows(IOException.class, () ->
                importSymbol(root, canonicalAllowlist, "src/Selected.ts", 1, 1));
    }

    @Test
    void rejectsAncestorDirectoryReplacedBySymlinkAfterAllowlistSelection() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("ancestor-link-project"));
        Path sourceDirectory = Files.createDirectories(root.resolve("src"));
        Path source = sourceDirectory.resolve("Selected.ts");
        Files.writeString(source, "safe");
        Set<String> canonicalAllowlist = Set.of("src/Selected.ts");

        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-dir"));
        Files.writeString(outsideDirectory.resolve("Selected.ts"), "outside secret");
        Files.delete(source);
        Files.delete(sourceDirectory);
        assumeSymlink(sourceDirectory, outsideDirectory);

        assertThrows(IOException.class, () ->
                importSymbol(root, canonicalAllowlist, "src/Selected.ts", 1, 1));
    }

    @Test
    void rejectsFileDeletedAfterAllowlistSelectionInsteadOfSkippingRangeValidation() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("deleted-project"));
        Path source = root.resolve("src/Deleted.ts");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "safe");
        Set<String> canonicalAllowlist = Set.of("src/Deleted.ts");
        Files.delete(source);

        assertThrows(IOException.class, () ->
                importSymbol(root, canonicalAllowlist, "src/Deleted.ts", 1, 1));
    }

    @Test
    void rejectsTraversalInCanonicalIndexedFileAllowlist() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("traversal-project"));
        Files.writeString(root.resolve("Safe.ts"), "safe");

        assertThrows(IOException.class, () ->
                importSymbol(root, Set.of("src/../Safe.ts"), "Safe.ts", 1, 1));
    }

    private static CodeIntelligenceSnapshot importSymbol(
            Path root,
            Set<String> indexedProjectFiles,
            String relativePath,
            int startLine,
            int endLine) throws Exception {
        Path canonicalRoot = root.toRealPath();
        ObjectNode document = JSON.createObjectNode();
        document.put("contractVersion", "1");
        document.put("producer", "MINOS");
        document.putObject("project").put("rootPath", canonicalRoot.toString());
        ObjectNode symbol = document.putArray("symbols").addObject();
        symbol.put("resolutionStatus", "RESOLVED");
        symbol.put("kind", "CLASS");
        symbol.put("filePath", relativePath);
        symbol.put("startLine", startLine);
        symbol.put("endLine", endLine);
        symbol.put("name", "Fixture");
        symbol.put("qualifiedName", "demo.Fixture");
        document.putArray("relations");

        return new MinosCodeIndexImporter().importPayload(
                canonicalRoot,
                indexedProjectFiles,
                JSON.writeValueAsString(document));
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
