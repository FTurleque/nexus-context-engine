package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLanguageTest {

    @Test
    void detectsSupportedSourceExtensionsCaseInsensitively() {
        assertEquals(SourceLanguage.JAVA, SourceLanguage.detect(Path.of("App.java")).orElseThrow());
        assertEquals(SourceLanguage.MARKDOWN, SourceLanguage.detect(Path.of("README.MD")).orElseThrow());
        assertEquals(SourceLanguage.KOTLIN, SourceLanguage.detect(Path.of("build.gradle.kts")).orElseThrow());
        assertEquals(SourceLanguage.TYPESCRIPT, SourceLanguage.detect(Path.of("component.test.tsx")).orElseThrow());
        assertEquals(SourceLanguage.JAVASCRIPT, SourceLanguage.detect(Path.of("vite.config.mjs")).orElseThrow());
        assertEquals(SourceLanguage.PYTHON, SourceLanguage.detect(Path.of("service.PY")).orElseThrow());
        assertEquals(SourceLanguage.SQL, SourceLanguage.detect(Path.of("migration.sql")).orElseThrow());
        assertTrue(SourceLanguage.detect(Path.of("legacy.rb")).isEmpty());
    }
}
