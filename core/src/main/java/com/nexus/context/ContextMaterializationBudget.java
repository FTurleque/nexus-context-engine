package com.nexus.context;

import com.nexus.security.SafeFileIO;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Mutable per-request budget for physical task-source materialization.
 *
 * <p>The same instance is propagated across every project participating in a
 * federated request. Reads are charged as bytes are physically traversed. When
 * the budget is exactly exhausted, one sentinel byte may be read to distinguish
 * EOF from overflow; that byte is never admitted into the returned content.</p>
 */
public final class ContextMaterializationBudget {

    private static final int READ_BUFFER_SIZE = 16 * 1024;

    private final ContextMaterializationLimits limits;
    private int openedFiles;
    private long cumulativeBytes;

    ContextMaterializationBudget(ContextMaterializationLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public synchronized String readUtf8NoFollow(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        openedFiles++;
        try (InputStream input = SafeFileIO.newInputStreamNoFollow(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            while (true) {
                long remaining = limits.maxCumulativeBytes() - cumulativeBytes;
                if (remaining == 0L) {
                    if (input.read() < 0) {
                        break;
                    }
                    throw exceeded(path);
                }

                int allowed = (int) Math.min(READ_BUFFER_SIZE, remaining);
                int read = input.read(buffer, 0, allowed);
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

    public synchronized Snapshot snapshot() {
        return new Snapshot(openedFiles, cumulativeBytes, remainingBytes());
    }

    private ContextMaterializationLimitExceededException exceeded(Path path) {
        return new ContextMaterializationLimitExceededException(
                "Budget de matérialisation du contexte dépassé: maximum "
                        + limits.maxCumulativeBytes() + " octets cumulés sur " + path);
    }

    public record Snapshot(int openedFiles, long cumulativeBytes, long remainingBytes) {
    }
}
