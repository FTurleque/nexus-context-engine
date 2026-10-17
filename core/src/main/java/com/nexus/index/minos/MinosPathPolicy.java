package com.nexus.index.minos;

import com.nexus.paths.RepositoryPath;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import static com.nexus.index.minos.MinosCodeIndexImporter.*;

/** Composant interne du contrat d’import MINOS. */
final class MinosPathPolicy {
    static Set<String> canonicalIndexedFiles(Set<String> indexedProjectFiles) throws IOException {
        Objects.requireNonNull(indexedProjectFiles, "indexedProjectFiles");
        Set<String> safe = new LinkedHashSet<>();
        for (String value : indexedProjectFiles) {
            if (value == null || value.isBlank()) {
                throw new IOException("NEXUS canonical indexed file path must not be blank");
            }
            try {
                safe.add(new RepositoryPath(value).value());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("NEXUS canonical indexed file path is invalid", invalid);
            }
        }
        return Set.copyOf(safe);
    }

    static String safeRelativePath(Set<String> safeProjectFiles, String exportedPath) {
        String normalized = safeRelativePathSyntax(exportedPath);
        return normalized != null && safeProjectFiles.contains(normalized) ? normalized : null;
    }

    static String safeRelativePathSyntax(String exportedPath) {
        try {
            return RepositoryPath.fromProvider(exportedPath).value();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    static Path normalizedAbsolutePath(String value, String field) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IOException("MINOS export " + field + " must be canonical and absolute");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IOException("MINOS export contains an invalid " + field, exception);
        }
    }
}
