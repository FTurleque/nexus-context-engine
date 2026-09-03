package com.nexus.index.jdt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdtConfigurationBoundsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsMaximumTimeoutAndFallsBackOnlyWhenUnset() {
        assertEquals(
                JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS,
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveLong(
                        JdtLanguageServerCodeIntelligenceProvider.TIMEOUT_ENVIRONMENT_VARIABLE,
                        Long.toString(JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS),
                        120L,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS));
        assertEquals(
                120L,
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveLong(
                        JdtLanguageServerCodeIntelligenceProvider.TIMEOUT_ENVIRONMENT_VARIABLE,
                        "   ",
                        120L,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS));
    }

    @Test
    void rejectsInvalidOrOversizedTimeoutInsteadOfSilentlyFallingBack() {
        assertThrows(IllegalArgumentException.class, () ->
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveLong(
                        JdtLanguageServerCodeIntelligenceProvider.TIMEOUT_ENVIRONMENT_VARIABLE,
                        "abc",
                        120L,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS));
        assertThrows(IllegalArgumentException.class, () ->
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveLong(
                        JdtLanguageServerCodeIntelligenceProvider.TIMEOUT_ENVIRONMENT_VARIABLE,
                        Long.toString(JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS + 1L),
                        120L,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS));
    }

    @Test
    void acceptsMaximumSymbolCountAndRejectsOversizedValue() {
        assertEquals(
                JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS,
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveInt(
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS_ENVIRONMENT_VARIABLE,
                        Integer.toString(JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS),
                        250,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS));
        assertThrows(IllegalArgumentException.class, () ->
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveInt(
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS_ENVIRONMENT_VARIABLE,
                        Integer.toString(JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS + 1),
                        250,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS));
    }

    @Test
    void configurationEnforcesTheSameHardCaps() {
        Path installation = temporaryDirectory.resolve("jdtls");
        Path workspaces = temporaryDirectory.resolve("workspaces");

        assertThrows(IllegalArgumentException.class, () ->
                new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                        installation,
                        workspaces,
                        "java",
                        Duration.ofSeconds(JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS + 1L),
                        250));
        assertThrows(IllegalArgumentException.class, () ->
                new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                        installation,
                        workspaces,
                        "java",
                        Duration.ofSeconds(120),
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS + 1));
    }
}
