package com.nexus.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Canonicalise un repository et protège toutes les lectures effectuées par NEXUS.
 *
 * <p>Les liens symboliques sont volontairement refusés sous la racine du projet.
 * Cette politique évite qu'un fichier apparemment versionné dans le repository
 * redirige une lecture vers un chemin extérieur. La racine elle-même peut être
 * fournie via un lien symbolique : elle est résolue une fois, puis cette cible
 * canonique devient la frontière de confiance.</p>
 */
public final class ProjectPathGuard {

    private final Path root;

    public ProjectPathGuard(Path projectRoot) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path canonicalRoot = projectRoot.toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Le chemin du projet n'est pas un répertoire : " + canonicalRoot);
        }
        this.root = canonicalRoot;
    }

    /** Racine canonique qui constitue la frontière de confiance du repository. */
    public Path root() {
        return root;
    }

    /** Résout un chemin relatif sans autoriser de sortie lexicale du repository. */
    public Path resolve(Path relativePath) throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isAbsolute()) {
            throw new IOException("Chemin absolu interdit dans le repository : " + relativePath);
        }
        return requireContained(root.resolve(relativePath).normalize());
    }

    /**
     * Retourne le fichier canonique uniquement s'il est régulier, contenu dans le
     * repository et si aucun composant sous la racine n'est un lien symbolique.
     */
    public Path requireRegularFile(Path candidate) throws IOException {
        Path contained = requireContained(candidate);
        rejectSymbolicLinkComponents(contained);
        Path real = contained.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Chemin réel hors repository : " + candidate);
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fichier absent ou non régulier : " + repositoryPath(contained));
        }
        return real;
    }

    /**
     * Retourne le répertoire canonique uniquement s'il reste contenu dans le
     * repository et ne traverse aucun lien symbolique sous la racine.
     */
    public Path requireDirectory(Path candidate) throws IOException {
        Path contained = requireContained(candidate);
        rejectSymbolicLinkComponents(contained);
        Path real = contained.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Chemin réel hors repository : " + candidate);
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Répertoire absent ou invalide : " + repositoryPath(contained));
        }
        return real;
    }

    /** Indique explicitement si le chemin contient un lien symbolique sous la racine. */
    public boolean containsSymbolicLink(Path candidate) throws IOException {
        Path contained = requireContained(candidate);
        Path current = root;
        for (Path segment : root.relativize(contained)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    /** Chemin repository stable pour les diagnostics. */
    public String repositoryPath(Path candidate) {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            return absolute.toString().replace('\\', '/');
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    private Path requireContained(Path candidate) throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new IOException("Chemin hors repository : " + absolute);
        }
        return absolute;
    }

    private void rejectSymbolicLinkComponents(Path candidate) throws IOException {
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Lien symbolique interdit dans le repository : " + repositoryPath(current));
            }
        }
    }
}
