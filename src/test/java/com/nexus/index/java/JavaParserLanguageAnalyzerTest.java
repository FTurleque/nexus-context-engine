package com.nexus.index.java;

import com.nexus.index.AnalysisResult;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void parsesJava21TextBlocks() throws IOException {
        Path source = tempDir.resolve("ModernSyntax.java");
        String sourceCode = "package demo.modern;\n"
                + "public class ModernSyntax {\n"
                + "    private static final String TEMPLATE = \"\"\"\n"
                + "            hello from a text block\n"
                + "            \"\"\";\n"
                + "    public String template() { return TEMPLATE; }\n"
                + "}\n";
        Files.writeString(source, sourceCode);

        JavaParserLanguageAnalyzer analyzer = new JavaParserLanguageAnalyzer();
        AnalysisResult result = analyzer.analyze(tempDir, source);

        assertTrue(result.symbols().stream().anyMatch(symbol ->
                symbol.kind() == SymbolKind.CLASS && symbol.name().equals("ModernSyntax")));
        assertTrue(result.symbols().stream().anyMatch(symbol ->
                symbol.kind() == SymbolKind.METHOD && symbol.name().equals("template")));
    }

    @Test
    void rejectsRecoveredPartialAstWhenSyntaxIsInvalid() throws IOException {
        Path source = tempDir.resolve("Broken.java");
        Files.writeString(source, """
                package demo;
                class Broken {
                    void run( {
                }
                """);

        JavaParserLanguageAnalyzer analyzer = new JavaParserLanguageAnalyzer();

        assertThrows(IOException.class, () -> analyzer.analyze(tempDir, source));
    }
}
