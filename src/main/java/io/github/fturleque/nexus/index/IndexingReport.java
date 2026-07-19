package io.github.fturleque.nexus.index;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

public record IndexingReport(
        UUID projectId,
        int scannedFiles,
        int changedFiles,
        int removedFiles,
        boolean fullSearchRebuild,
        IndexStatistics statistics,
        Duration duration) {

    public IndexingReport {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(duration, "duration");
    }
}
