package com.nexus.index.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserSymbolRangeValidationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsOnlyStrictlyValidOneBasedRanges() throws Exception {
        Path source = temporaryDirectory.resolve("Fixture.java");
        Files.writeString(source, """
                package demo;
                class Fixture {
                    Fixture() {}
                    void run() {}
                }
                """);

        var result = new JavaParserLanguageAnalyzer().analyze(temporaryDirectory, source);

        assertFalse(result.symbols().isEmpty());
        assertTrue(result.symbols().stream().allMatch(symbol ->
                symbol.startLine() >= 1 && symbol.endLine() >= symbol.startLine()));
    }
}
