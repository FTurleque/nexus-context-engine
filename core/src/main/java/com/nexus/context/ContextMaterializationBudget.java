package com.nexus.context;

import com.nexus.security.SafeFileIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Mutable per-request budget for physical task-source materialization.
 *
 * <p>The same instance is propagated across every project participating in a
 * federated request. Reads are bounded by cumulative bytes, physical file opens
 * and elapsed materialization time. When the byte budget is exactly exhausted,
 * one sentinel byte may be read to distinguish EOF from overflow; that byte is
 * never admitted into the returned content.</p>
 */
public final class ContextMaterializationBudget {

    private static final int READ_BUFFER_SIZE = 16 * 1024;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final ContextMaterializationLimits limits;
    private final LongSupplier nanoClock;
    private final long startedAtNanos;
    private int openedFiles;
    private long cumulativeBytes;

    ContextMaterializationBudget(ContextMaterializationLimits limits) {
        this(limits, System::nanoTime);
    }

    ContextMaterializationBudget(ContextMaterializationLimits limits, LongSupplier nanoClock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.startedAtNanos = nanoClock.getAsLong();
    }

    public synchronized String readUtf8NoFollow(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        consumeOpen(path);
        try (InputStream input = SafeFileIO.newInputStreamNoFollow(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            while (true) {
                checkpoint(path);
                long remaining = limits.maxCumulativeBytes() - cumulativeBytes;
                if (remaining == 0L) {
                    int sentinel = input.read();
                    checkpoint(path);
                    if (sentinel < 0) {
                        break;
                    }
                    throw byteLimitExceeded(path);
                }

                int allowed = (int) Math.min(READ_BUFFER_SIZE, remaining);
                int read = input.read(buffer, 0, allowed);
                checkpoint(path);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                cumulativeBytes += read;
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    public ContextMaterializationLimits limits() {
        return limits;
    }

    public synchronized long remainingBytes() {
        return limits.maxCumulativeBytes() - cumulativeBytes;
    }

    public synchronized int remainingFiles() {
        return limits.maxOpenedFiles() - openedFiles;
    }

    public synchronized long elapsedMillis() {
        return elapsedMillis(nanoClock.getAsLong());
    }

    public synchronized Snapshot snapshot() {
        long elapsed = elapsedMillis(nanoClock.getAsLong());
        return new Snapshot(
                openedFiles,
                cumulativeBytes,
                remainingBytes(),
                remainingFiles(),
                elapsed,
                Math.max(0L, limits.maxDurationMillis() - elapsed));
    }

    private void consumeOpen(Path path) throws ContextMaterializationLimitExceededException {
        checkpoint(path);
        if (openedFiles >= limits.maxOpenedFiles()) {
            throw new ContextMaterializationLimitExceededException(
                    "Budget de matérialisation du contexte dépassé: maximum "
                            + limits.maxOpenedFiles() + " fichiers ouverts sur " + path);
        }
        openedFiles++;
    }

    private void checkpoint(Path path) throws ContextMaterializationLimitExceededException {
        long elapsed = elapsedMillis(nanoClock.getAsLong());
        if (elapsed >= limits.maxDurationMillis()) {
            throw new ContextMaterializationLimitExceededException(
                    "Budget de matérialisation du contexte dépassé: maximum "
                            + limits.maxDurationMillis() + " millisecondes sur " + path);
        }
    }

    private long elapsedMillis(long nowNanos) {
        long elapsedNanos = nowNanos - startedAtNanos;
        if (elapsedNanos <= 0L) {
            return 0L;
        }
        return elapsedNanos / NANOS_PER_MILLI;
    }

    private ContextMaterializationLimitExceededException byteLimitExceeded(Path path) {
        return new ContextMaterializationLimitExceededException(
                "Budget de matérialisation du contexte dépassé: maximum "
                        + limits.maxCumulativeBytes() + " octets cumulés sur " + path);
    }

    public record Snapshot(
            int openedFiles,
            long cumulativeBytes,
            long remainingBytes,
            int remainingFiles,
            long elapsedMillis,
            long remainingDurationMillis) {
    }
}
