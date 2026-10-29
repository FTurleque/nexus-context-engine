package com.nexus.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolicLinksTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void supportIsOptionalOnADeveloperWorkstation() {
        assertFalse(SymbolicLinks.required(Map.of(), null));
        assertFalse(SymbolicLinks.required(Map.of("CI", ""), "false"));
        assertFalse(SymbolicLinks.required(Map.of("CI", " "), null));
    }

    @Test
    void supportIsRequiredWhenCiIsDefined() {
        assertTrue(SymbolicLinks.required(Map.of("CI", "true"), null));
        assertTrue(SymbolicLinks.required(Map.of("CI", "1"), null));
        assertTrue(SymbolicLinks.required(Map.of("CI", "TRUE"), "false"));
    }

    @Test
    void ciExplicitlyDisabledDoesNotRequireSupport() {
        assertFalse(SymbolicLinks.required(Map.of("CI", "false"), null));
        assertFalse(SymbolicLinks.required(Map.of("CI", "0"), null));
    }

    @Test
    void dedicatedPropertyRequiresSupportOutsideCi() {
        assertTrue(SymbolicLinks.required(Map.of(), "true"));
        assertTrue(SymbolicLinks.required(Map.of("CI", "false"), "TRUE"));
    }

    @Test
    void unavailableLinkFailsWhenSupportIsRequired() throws Exception {
        Path occupied = Files.writeString(temporaryDirectory.resolve("occupied"), "file");
        Path target = temporaryDirectory.resolve("target");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> SymbolicLinks.createOrSkip(occupied, target, true));

        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
    }

    @Test
    void unavailableLinkAbortsWhenSupportIsOptional() throws Exception {
        Path occupied = Files.writeString(temporaryDirectory.resolve("occupied"), "file");
        Path target = temporaryDirectory.resolve("target");

        assertThrows(TestAbortedException.class, () -> SymbolicLinks.createOrSkip(occupied, target, false));
    }
}
