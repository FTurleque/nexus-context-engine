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
                throw new IOException("Le chemin canonique d'un fichier indexé par NEXUS ne peut pas être vide");
            }
            try {
                safe.add(new RepositoryPath(value).value());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Chemin canonique de fichier indexé par NEXUS invalide", invalid);
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
                throw new IOException("Le champ " + field + " de l'export MINOS doit être canonique et absolu");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IOException("L'export MINOS contient un champ " + field + " invalide", exception);
        }
    }
}
