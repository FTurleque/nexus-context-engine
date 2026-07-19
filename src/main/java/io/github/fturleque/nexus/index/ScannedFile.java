package io.github.fturleque.nexus.index;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record ScannedFile(
        Path absolutePath,
        String relativePath,
        String language,
        long sizeBytes,
        String contentHash,
        Instant modifiedAt,
        int estimatedTokens,
        FileCategory category) {

    public ScannedFile {
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        Objects.requireNonNull(category, "category");
        absolutePath = absolutePath.toAbsolutePath().normalize();
    }
}
