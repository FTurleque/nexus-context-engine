package io.github.fturleque.nexus.index;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IndexedFile(
        long id,
        UUID projectId,
        String relativePath,
        String language,
        long sizeBytes,
        String contentHash,
        Instant modifiedAt,
        int estimatedTokens,
        FileCategory category) {

    public IndexedFile {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Objects.requireNonNull(category, "category");
    }
}
