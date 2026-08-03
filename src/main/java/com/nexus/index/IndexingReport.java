package com.nexus.index;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record IndexingReport(
        UUID projectId,
        int scannedFiles,
        int changedFiles,
        int removedFiles,
        boolean fullSearchRebuild,
        IndexStatistics statistics,
        Duration duration,
        int skippedFiles,
        List<String> diagnostics) {

    public IndexingReport {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (skippedFiles < 0) {
            throw new IllegalArgumentException("skippedFiles must not be negative");
        }
        diagnostics = List.copyOf(diagnostics);
    }

    public IndexingReport(
            UUID projectId,
            int scannedFiles,
            int changedFiles,
            int removedFiles,
            boolean fullSearchRebuild,
            IndexStatistics statistics,
            Duration duration) {
        this(projectId, scannedFiles, changedFiles, removedFiles,
                fullSearchRebuild, statistics, duration, 0, List.of());
    }
}
