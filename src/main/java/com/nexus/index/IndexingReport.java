package com.nexus.index;

import java.time.Duration;
import java.util.List;
import java.util.Map;
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
        List<String> diagnostics,
        Map<String, Long> providerDurationsMs) {

    public IndexingReport {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(providerDurationsMs, "providerDurationsMs");
        if (skippedFiles < 0) {
            throw new IllegalArgumentException("skippedFiles must not be negative");
        }
        if (providerDurationsMs.values().stream().anyMatch(value -> value == null || value < 0L)) {
            throw new IllegalArgumentException("provider durations must not be negative");
        }
        diagnostics = List.copyOf(diagnostics);
        providerDurationsMs = Map.copyOf(providerDurationsMs);
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
                fullSearchRebuild, statistics, duration, 0, List.of(), Map.of());
    }

    public IndexingReport(
            UUID projectId,
            int scannedFiles,
            int changedFiles,
            int removedFiles,
            boolean fullSearchRebuild,
            IndexStatistics statistics,
            Duration duration,
            int skippedFiles,
            List<String> diagnostics) {
        this(projectId, scannedFiles, changedFiles, removedFiles,
                fullSearchRebuild, statistics, duration, skippedFiles, diagnostics, Map.of());
    }
}
