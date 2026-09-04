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
        String oversizedTimeout = Long.toString(JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS + 1L);
        assertThrows(IllegalArgumentException.class, () ->
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveLong(
                        JdtLanguageServerCodeIntelligenceProvider.TIMEOUT_ENVIRONMENT_VARIABLE,
                        "abc",
                        120L,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS));
        assertThrows(IllegalArgumentException.class, () ->
                JdtLanguageServerCodeIntelligenceProvider.parseBoundedPositiveLong(
                        JdtLanguageServerCodeIntelligenceProvider.TIMEOUT_ENVIRONMENT_VARIABLE,
                        oversizedTimeout,
                        120L,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS));
    }

    @Test
    void acceptsMaximumSymbolCountAndRejectsOversizedValue() {
        String oversizedSymbols = Integer.toString(JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS + 1);
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
                        oversizedSymbols,
                        250,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS));
    }

    @Test
    void configurationEnforcesTheSameHardCaps() {
        Path installation = temporaryDirectory.resolve("jdtls");
        Path workspaces = temporaryDirectory.resolve("workspaces");

        Duration oversizedTimeout = Duration.ofSeconds(
                JdtLanguageServerCodeIntelligenceProvider.MAX_TIMEOUT_SECONDS + 1L);
        assertThrows(IllegalArgumentException.class, () ->
                new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                        installation, workspaces, "java", oversizedTimeout, 250));
        Duration validTimeout = Duration.ofSeconds(120);
        assertThrows(IllegalArgumentException.class, () ->
                new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                        installation, workspaces, "java", validTimeout,
                        JdtLanguageServerCodeIntelligenceProvider.MAX_SYMBOLS + 1));
    }
}
