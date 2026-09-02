package com.nexus.context.source;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Mutable per-context accounting object shared by all native providers so the
 * configured limits apply to cumulative work, not independently per provider.
 */
public final class ContextDiscoveryBudget {

    private final ContextDiscoveryLimits limits;
    private final long startedAtNanos;
    private final long deadlineNanos;
    private int visitedEntries;
    private int candidateResources;
    private long cumulativeBytes;

    ContextDiscoveryBudget(ContextDiscoveryLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.startedAtNanos = System.nanoTime();
        this.deadlineNanos = startedAtNanos + limits.maxElapsedMillis() * 1_000_000L;
    }

    public synchronized void visit(Path path) throws ContextDiscoveryLimitExceededException {
        checkpoint(path, "visite");
        if (visitedEntries >= limits.maxVisitedEntries()) {
            throw exceeded("entrées visitées", limits.maxVisitedEntries(), path);
        }
        visitedEntries++;
    }

    public synchronized void candidate(Path path) throws ContextDiscoveryLimitExceededException {
        checkpoint(path, "candidat");
        if (candidateResources >= limits.maxCandidateResources()) {
            throw exceeded("ressources candidates", limits.maxCandidateResources(), path);
        }
        candidateResources++;
    }

    public synchronized void bytes(Path path, long bytes) throws ContextDiscoveryLimitExceededException {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes doit être positif ou nul");
        }
        checkpoint(path, "lecture");
        if (bytes > limits.maxCumulativeBytes() - cumulativeBytes) {
            throw exceeded("octets cumulés", limits.maxCumulativeBytes(), path);
        }
        cumulativeBytes += bytes;
    }

    public synchronized void checkpoint() throws ContextDiscoveryLimitExceededException {
        checkpoint(null, "checkpoint");
    }

    public synchronized Snapshot snapshot() {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
        return new Snapshot(
                visitedEntries,
                candidateResources,
                cumulativeBytes,
                elapsedNanos / 1_000_000L);
    }

    public ContextDiscoveryLimits limits() {
        return limits;
    }

    private void checkpoint(Path path, String operation) throws ContextDiscoveryLimitExceededException {
        if (System.nanoTime() - deadlineNanos > 0L) {
            String location = path == null ? "" : " sur " + path;
            throw new ContextDiscoveryLimitExceededException(
                    "Budget de découverte native dépassé: délai maximal de "
                            + limits.maxElapsedMillis() + " ms pendant " + operation + location);
        }
    }

    private static ContextDiscoveryLimitExceededException exceeded(String dimension, long maximum, Path path) {
        String location = path == null ? "" : " sur " + path;
        return new ContextDiscoveryLimitExceededException(
                "Budget de découverte native dépassé: maximum " + maximum + " " + dimension + location);
    }

    public record Snapshot(
            int visitedEntries,
            int candidateResources,
            long cumulativeBytes,
            long elapsedMillis) {
    }
}
