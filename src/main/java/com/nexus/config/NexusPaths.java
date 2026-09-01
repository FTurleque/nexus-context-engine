package com.nexus.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Centralise les emplacements de données locales utilisés par NEXUS.
 */
public record NexusPaths(Path home) {

    public static final String HOME_PROPERTY = "nexus.home";
    public static final String HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME";

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    public NexusPaths {
        Objects.requireNonNull(home, "home");
        home = home.toAbsolutePath().normalize();
    }

    public static NexusPaths fromEnvironment() {
        String configuredHome = System.getProperty(HOME_PROPERTY);
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = System.getenv(HOME_ENVIRONMENT_VARIABLE);
        }
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = Path.of(System.getProperty("user.home"), ".nexus").toString();
        }
        return new NexusPaths(Path.of(configuredHome));
    }

    /**
     * Crée les répertoires persistants NEXUS et les rend privés sur les systèmes POSIX.
     *
     * <p>Sur les systèmes sans vue POSIX (notamment Windows), NEXUS conserve les ACL natives
     * héritées du profil utilisateur au lieu de les remplacer de manière destructive. Le home est
     * toujours refusé lorsqu'il est lui-même un lien symbolique.</p>
     */
    public void ensurePrivateStorage() throws IOException {
        ensurePrivateDirectory(home);
        ensurePrivateDirectory(indexesDirectory());
        ensurePrivateDirectory(locksDirectory());
    }

    /** Rend un fichier persistant privé lorsque le système de fichiers expose les permissions POSIX. */
    public void hardenPrivateFile(Path file) throws IOException {
        Path normalized = requireInsideHome(file);
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Refus d'un fichier NEXUS symbolique : " + normalized);
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fichier NEXUS régulier attendu : " + normalized);
        }
        applyPosixPermissions(normalized, PRIVATE_FILE_PERMISSIONS);
    }

    public Path databaseFile() {
        return home.resolve("nexus.db");
    }

    public Path indexesDirectory() {
        return home.resolve("indexes");
    }

    public Path locksDirectory() {
        return home.resolve("locks");
    }

    public Path projectIndexLock(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return locksDirectory().resolve(projectId + ".index.lock");
    }

    public Path projectLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return indexesDirectory().resolve(projectId.toString()).resolve("lucene");
    }

    public Path projectSemanticLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return indexesDirectory().resolve(projectId.toString()).resolve("semantic-lucene");
    }

    private void ensurePrivateDirectory(Path directory) throws IOException {
        Path normalized = requireInsideHome(directory);
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Refus d'un répertoire NEXUS symbolique : " + normalized);
        }
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Répertoire NEXUS attendu : " + normalized);
        }
        applyPosixPermissions(normalized, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private Path requireInsideHome(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!normalized.startsWith(home)) {
            throw new IOException("Le chemin persistant sort de NEXUS_HOME : " + normalized);
        }
        return normalized;
    }

    private static void applyPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows et certains filesystems ne fournissent pas de vue POSIX. On ne remplace
            // pas leurs ACL natives : une réécriture naïve pourrait retirer SYSTEM/Administrators.
        }
    }
}
