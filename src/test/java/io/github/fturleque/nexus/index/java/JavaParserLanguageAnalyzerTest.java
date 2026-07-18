package io.github.fturleque.nexus.index.java;

import io.github.fturleque.nexus.index.AnalysisResult;
import io.github.fturleque.nexus.index.RelationKind;
import io.github.fturleque.nexus.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserLanguageAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsJavaTypesMethodsAndImports() throws IOException {
        Path source = tempDir.resolve("UploadService.java");
        Files.writeString(source, """
                package demo.upload;

                import java.util.List;

                public class UploadService {
                    public List<String> parsePdf() {
                        return List.of();
                    }
                }
                """);

        JavaParserLanguageAnalyzer analyzer = new JavaParserLanguageAnalyzer();
        AnalysisResult result = analyzer.analyze(tempDir, source);

        assertEquals("java", result.language());
        assertTrue(result.symbols().stream().anyMatch(symbol ->
                symbol.kind() == SymbolKind.CLASS && symbol.name().equals("UploadService")));
        assertTrue(result.symbols().stream().anyMatch(symbol ->
                symbol.kind() == SymbolKind.METHOD && symbol.name().equals("parsePdf")));
        assertTrue(result.relations().stream().anyMatch(relation ->
                relation.kind() == RelationKind.IMPORTS && relation.target().equals("java.util.List")));
    }
}
