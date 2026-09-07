package com.nexus.context.source;

import com.nexus.security.SafeFileIO;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Mutable per-context accounting object shared by all native providers so the
 * configured limits apply to cumulative work, not independently per provider.
 */
public final class ContextDiscoveryBudget {

    private static final int READ_BUFFER_SIZE = 16 * 1024;

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

    /**
     * Lit un fichier UTF-8 en débitant exactement les octets physiquement traversés.
     *
     * <p>La lecture et le débit du budget partagent le même verrou. La dernière lecture
     * est limitée au budget restant plus un octet sentinelle : un fichier qui grossit
     * entre sa découverte et son ouverture ne peut donc plus contourner le plafond
     * cumulatif via un {@code Files.size()} devenu obsolète.</p>
     */
    public String readUtf8NoFollow(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (InputStream input = budgeted(SafeFileIO.newInputStreamNoFollow(path), path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Ouvre un reader borné dont chaque octet consommé est débité du budget partagé.
     * La borne physique spécifique permet aux parseurs progressifs (par exemple le
     * frontmatter d'un skill) de conserver leur propre limite en plus du budget global.
     */
    public BufferedReader newBufferedReaderNoFollow(
            Path path,
            Charset charset,
            long maxPhysicalBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(charset, "charset");
        InputStream input = SafeFileIO.newInputStreamNoFollow(path, maxPhysicalBytes);
        return new BufferedReader(new InputStreamReader(budgeted(input, path), charset));
    }

    private InputStream budgeted(InputStream input, Path path) {
        return new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                synchronized (ContextDiscoveryBudget.this) {
                    int allowed = allowedTraversal(path, 1);
                    if (allowed == 0) {
                        throw exceeded("octets cumulés", limits.maxCumulativeBytes(), path);
                    }
                    int value = super.read();
                    if (value >= 0) {
                        recordTraversal(path, 1L);
                    }
                    return value;
                }
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, buffer.length);
                if (length == 0) {
                    return 0;
                }
                synchronized (ContextDiscoveryBudget.this) {
                    int allowed = allowedTraversal(path, length);
                    int read = super.read(buffer, offset, allowed);
                    if (read > 0) {
                        recordTraversal(path, read);
                    }
                    return read;
                }
            }

            @Override
            public long skip(long byteCount) throws IOException {
                if (byteCount <= 0) {
                    return 0L;
                }
                synchronized (ContextDiscoveryBudget.this) {
                    int allowed = allowedTraversal(path, (int) Math.min(Integer.MAX_VALUE, byteCount));
                    long skipped = super.skip(Math.min(byteCount, allowed));
                    if (skipped > 0) {
                        recordTraversal(path, skipped);
                    }
                    return skipped;
                }
            }
        };
    }

    /**
     * Autorise au plus le budget restant plus un octet sentinelle afin de détecter
     * un dépassement sans lire un bloc arbitrairement grand au-delà du plafond.
     */
    private int allowedTraversal(Path path, int requested) throws ContextDiscoveryLimitExceededException {
        checkpoint(path, "lecture");
        long remaining = limits.maxCumulativeBytes() - cumulativeBytes;
        long detectable = remaining == Long.MAX_VALUE ? Long.MAX_VALUE : remaining + 1L;
        return (int) Math.min(requested, Math.min(Integer.MAX_VALUE, detectable));
    }

    private void recordTraversal(Path path, long bytes) throws ContextDiscoveryLimitExceededException {
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
