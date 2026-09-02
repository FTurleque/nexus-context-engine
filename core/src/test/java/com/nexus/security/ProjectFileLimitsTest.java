package com.nexus.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectFileLimitsTest {

    @Test
    void acceptsConfiguredValuesUpToTheHardMaximum() {
        assertEquals(1L, ProjectFileLimits.parseMaxFileSize("1"));
        assertEquals(
                ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES,
                ProjectFileLimits.parseMaxFileSize(Long.toString(ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES)));
    }

    @Test
    void rejectsNonPositiveAndExcessiveConfiguredValues() {
        assertThrows(IllegalArgumentException.class, () -> ProjectFileLimits.parseMaxFileSize("0"));
        assertThrows(IllegalArgumentException.class, () -> ProjectFileLimits.parseMaxFileSize("-1"));
        assertThrows(IllegalArgumentException.class, () -> ProjectFileLimits.parseMaxFileSize(
                Long.toString(ProjectFileLimits.MAX_CONFIGURABLE_FILE_SIZE_BYTES + 1L)));
    }

    @Test
    void rejectsMalformedConfiguredValues() {
        assertThrows(IllegalArgumentException.class, () -> ProjectFileLimits.parseMaxFileSize("not-a-number"));
    }
}
