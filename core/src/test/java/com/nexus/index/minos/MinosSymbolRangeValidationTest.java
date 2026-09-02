package com.nexus.index.minos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosSymbolRangeValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsZeroStartLine() throws Exception {
        Path root = projectWith("src/One.ts", "export const one = 1;");
        assertThrows(IOException.class, () -> importSymbol(root, "src/One.ts", 0, 1));
    }

    @Test
    void rejectsEndBeforeStart() throws Exception {
        Path root = projectWith("src/One.ts", "export const one = 1;");
        assertThrows(IOException.class, () -> importSymbol(root, "src/One.ts", 2, 1));
    }

    @Test
    void rejectsRangeBeyondCanonicalFileAndEmptyFile() throws Exception {
        Path root = projectWith("src/One.ts", "export const one = 1;");
        assertThrows(IOException.class, () -> importSymbol(root, "src/One.ts", 1, 100));

        Path emptyRoot = projectWith("src/Empty.ts", "");
        assertThrows(IOException.class, () -> importSymbol(emptyRoot, "src/Empty.ts", 1, 1));
    }

    @Test
    void acceptsSymbolCoveringLastLine() throws Exception {
        Path root = projectWith("src/Three.ts", "one\ntwo\nthree");
        var snapshot = importSymbol(root, "src/Three.ts", 3, 3);

        assertEquals(1, snapshot.symbols().size());
        assertEquals(3, snapshot.symbols().getFirst().symbol().startLine());
        assertEquals(3, snapshot.symbols().getFirst().symbol().endLine());
    }

    private Path projectWith(String relativePath, String content) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("project-" + Math.abs(relativePath.hashCode())));
        Path source = root.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return root.toRealPath();
    }

    private static com.nexus.index.CodeIntelligenceSnapshot importSymbol(
            Path root,
            String relativePath,
            int startLine,
            int endLine) throws Exception {
        ObjectNode document = JSON.createObjectNode();
        document.put("contractVersion", "1");
        document.put("producer", "MINOS");
        document.putObject("project").put("rootPath", root.toString());
        var symbols = document.putArray("symbols");
        ObjectNode symbol = symbols.addObject();
        symbol.put("resolutionStatus", "RESOLVED");
        symbol.put("kind", "CLASS");
        symbol.put("filePath", relativePath);
        symbol.put("startLine", startLine);
        symbol.put("endLine", endLine);
        symbol.put("name", "Fixture");
        symbol.put("qualifiedName", "demo.Fixture");
        document.putArray("relations");

        return new MinosCodeIndexImporter().importPayload(root, JSON.writeValueAsString(document));
    }
}
