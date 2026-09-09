package com.nexus.paths;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Identité repository : '/' sépare exclusivement les composants physiques.
 * Aucun changement de casse, de caractères, ni réparation de syntaxe.
 * La résolution est lexicale ; ProjectPathGuard/SafeFileIO restent obligatoires
 * pour les lectures et le refus des liens symboliques.
 */
public record RepositoryPath(String value) {
    public RepositoryPath {
        Objects.requireNonNull(value, "value");
        for (String component : value.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.indexOf('\0') >= 0) {
                throw invalid();
            }
        }
    }

    public static RepositoryPath fromPath(Path relative) {
        return new RepositoryPath(encode(relative));
    }

    /** La chaîne vide représente uniquement le scope racine, jamais un fichier. */
    public static String encode(Path relative) {
        Objects.requireNonNull(relative, "relative");
        if (relative.isAbsolute() || relative.getRoot() != null) throw invalid();
        if (relative.toString().isEmpty()) return "";
        StringJoiner result = new StringJoiner("/");
        for (Path component : relative) result.add(component.toString());
        return new RepositoryPath(result.toString()).value();
    }

    /** SCIP, Git et export MINOS : composants séparés par '/', même sous Windows. */
    public static RepositoryPath fromProvider(String slashPath) {
        Objects.requireNonNull(slashPath, "slashPath");
        if (slashPath.matches("^[A-Za-z]:.*") || slashPath.startsWith("\\\\")) throw invalid();
        return new RepositoryPath(slashPath);
    }

    /** Refuse un composant non représentable tel quel sur le filesystem cible. */
    public Path toPath(FileSystem fileSystem) {
        Path result = fileSystem.getPath("");
        for (String component : value.split("/", -1)) {
            Path part = fileSystem.getPath(component);
            if (part.isAbsolute() || part.getRoot() != null || part.getNameCount() != 1
                    || !part.toString().equals(component)) throw invalid();
            result = result.resolve(part);
        }
        if (!encode(result).equals(value)) throw invalid();
        return result;
    }

    public Path resolve(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path result = root.resolve(toPath(root.getFileSystem()));
        if (!result.startsWith(root)) throw invalid();
        return result;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid repository path");
    }
}
