package com.nexus.index.scan;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectScanLimitsTest {

    @Test
    void usesDocumentedDefaultsWhenEnvironmentIsAbsent() {
        ProjectScanLimits limits = ProjectScanLimits.from(Map.of());

        assertEquals(ProjectScanLimits.DEFAULT_MAX_FILES, limits.maxFiles());
        assertEquals(ProjectScanLimits.DEFAULT_MAX_TOTAL_BYTES, limits.maxTotalBytes());
    }

    @Test
    void parsesExplicitPositiveLimits() {
        ProjectScanLimits limits = ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE, "123",
                ProjectScanLimits.MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE, "456789"));

        assertEquals(123, limits.maxFiles());
        assertEquals(456789L, limits.maxTotalBytes());
    }

    @Test
    void acceptsHardLimitBoundaries() {
        ProjectScanLimits limits = ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE,
                Integer.toString(ProjectScanLimits.HARD_MAX_FILES),
                ProjectScanLimits.MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE,
                Long.toString(ProjectScanLimits.HARD_MAX_TOTAL_BYTES)));

        assertEquals(ProjectScanLimits.HARD_MAX_FILES, limits.maxFiles());
        assertEquals(ProjectScanLimits.HARD_MAX_TOTAL_BYTES, limits.maxTotalBytes());
    }

    @Test
    void rejectsLimitsBeyondHardSafetyCeilings() {
        assertThrows(IllegalArgumentException.class, () -> ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE,
                Integer.toString(ProjectScanLimits.HARD_MAX_FILES + 1))));
        assertThrows(IllegalArgumentException.class, () -> ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE,
                Long.toString(ProjectScanLimits.HARD_MAX_TOTAL_BYTES + 1))));

        assertThrows(IllegalArgumentException.class, () ->
                new ProjectScanLimits(ProjectScanLimits.HARD_MAX_FILES + 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                new ProjectScanLimits(1, ProjectScanLimits.HARD_MAX_TOTAL_BYTES + 1));
    }

    @Test
    void rejectsInvalidLimitsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE, "0")));
        assertThrows(IllegalArgumentException.class, () -> ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE, "not-a-number")));
        assertThrows(IllegalArgumentException.class, () -> ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE, "-1")));
        assertThrows(IllegalArgumentException.class, () -> ProjectScanLimits.from(Map.of(
                ProjectScanLimits.MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE, "overflow")));
    }
}
