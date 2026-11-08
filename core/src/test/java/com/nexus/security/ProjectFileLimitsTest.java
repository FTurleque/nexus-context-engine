package com.nexus.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectFileLimitsTest {

    private static long resolve(String configured) {
        return ProjectFileLimits.maxFileSize(Map.of(ProjectFileLimits.MAX_FILE_SIZE_ENVIRONMENT_VARIABLE, configured));
    }

    @Test
    void keepsTheDefaultWhenTheVariableIsAbsentOrBlank() {
        assertEquals(ProjectFileLimits.DEFAULT_MAX_FILE_SIZE_BYTES, ProjectFileLimits.maxFileSize(Map.of()));
        assertEquals(ProjectFileLimits.DEFAULT_MAX_FILE_SIZE_BYTES, resolve("  "));
    }

    @Test
    void acceptsConfiguredValuesUpToTheHardMaximum() {
        assertEquals(1L, resolve("1"));
        assertEquals(
                ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES,
                resolve(Long.toString(ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES)));
    }

    @Test
    void rejectsNonPositiveAndExcessiveConfiguredValues() {
        assertThrows(IllegalArgumentException.class, () -> resolve("0"));
        assertThrows(IllegalArgumentException.class, () -> resolve("-1"));
        IllegalArgumentException excessive = assertThrows(IllegalArgumentException.class, () -> resolve(
                Long.toString(ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES + 1L)));
        assertEquals(ProjectFileLimits.MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être compris entre 1 et "
                + ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES + " octets", excessive.getMessage());
    }

    @Test
    void rejectsMalformedConfiguredValues() {
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class, () -> resolve("not-a-number"));
        assertEquals(ProjectFileLimits.MAX_FILE_SIZE_ENVIRONMENT_VARIABLE + " doit être un entier en octets",
                malformed.getMessage());
    }
}
