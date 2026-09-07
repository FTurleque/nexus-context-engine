package com.nexus.config;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Centralise les emplacements de données locales utilisés par NEXUS.
 */
public record NexusPaths(Path home) {

    private static final System.Logger LOGGER = System.getLogger(NexusPaths.class.getName());
    private static final String PROJECT_ID_ARGUMENT = "projectId";

    public static final String HOME_PROPERTY = "nexus.home";
    public static final String HOME_ENVIRONMENT_VARIABLE = "NEXUS_HOME";

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<AclEntryPermission> SENSITIVE_ACL_PERMISSIONS = Set.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER);

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
     * héritées du profil utilisateur au lieu de les remplacer de manière destructive. Chaque
     * chemin sensible effectivement créé ou durci est inspecté lorsqu'une vue ACL est disponible,
     * de sorte qu'une ACL explicite plus large sur un enfant ne puisse pas être masquée par une
     * vérification limitée au seul {@code NEXUS_HOME}.</p>
     */
    public void ensurePrivateStorage() throws IOException {
        ensurePrivateDirectory(home);
        ensurePrivateDirectory(indexesDirectory());
        ensurePrivateDirectory(locksDirectory());
    }

    /**
     * Crée un répertoire persistant sous {@code NEXUS_HOME} sans suivre de lien symbolique
     * préexistant dans sa hiérarchie relative. Chaque composant enfant est créé et revalidé
     * séparément afin qu'un symlink tel que {@code indexes/<projectId>} ne puisse pas rediriger
     * les écritures Lucene hors du stockage NEXUS.
     */
    public void ensurePrivateDirectory(Path directory) throws IOException {
        Path normalized = requireInsideHome(directory);
        ensureHomeDirectory();
        if (normalized.equals(home)) {
            return;
        }

        Path current = home;
        for (Path segment : home.relativize(normalized)) {
            current = current.resolve(segment);
            ensurePrivateChildDirectory(current);
            warnIfAclMayBeShared(current);
        }
    }

    /**
     * Prépare un fichier persistant privé avant qu'un composant natif ou JDBC ne l'ouvre.
     *
     * <p>Le parent est d'abord validé sans suivre de lien symbolique. Le fichier est ensuite créé
     * atomiquement lorsqu'il est absent, puis revalidé avec {@link LinkOption#NOFOLLOW_LINKS}.
     * Un lien symbolique préexistant est donc refusé avant qu'un consommateur puisse suivre sa
     * cible.</p>
     */
    public void ensurePrivateFile(Path file) throws IOException {
        Path normalized = requireInsideHome(file);
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Parent du fichier NEXUS introuvable : " + normalized);
        }
        ensurePrivateDirectory(parent);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(normalized);
            } catch (FileAlreadyExistsException ignored) {
                // Une création concurrente est acceptable uniquement si la revalidation
                // NOFOLLOW_LINKS ci-dessous confirme qu'il s'agit bien d'un fichier réel.
            }
        }
        validateRegularFile(normalized);
        applyPosixPermissions(normalized, PRIVATE_FILE_PERMISSIONS);
        warnIfAclMayBeShared(normalized);
    }

    /** Rend un fichier persistant privé lorsque le système de fichiers expose les permissions POSIX. */
    public void hardenPrivateFile(Path file) throws IOException {
        Path normalized = requireInsideHome(file);
        validateRegularFile(normalized);
        applyPosixPermissions(normalized, PRIVATE_FILE_PERMISSIONS);
        warnIfAclMayBeShared(normalized);
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
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        return locksDirectory().resolve(projectId + ".index.lock");
    }

    public Path projectLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        return indexesDirectory().resolve(projectId.toString()).resolve("lucene");
    }

    public Path projectSemanticLuceneIndex(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_ARGUMENT);
        return indexesDirectory().resolve(projectId.toString()).resolve("semantic-lucene");
    }

    private void ensureHomeDirectory() throws IOException {
        if (Files.isSymbolicLink(home)) {
            throw new IOException("Refus d'un répertoire NEXUS symbolique : " + home);
        }
        Files.createDirectories(home);
        validateDirectory(home);
        applyPosixPermissions(home, PRIVATE_DIRECTORY_PERMISSIONS);
        warnIfAclMayBeShared(home);
    }

    private static void ensurePrivateChildDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException ignored) {
                // Une création concurrente est acceptable uniquement si la revalidation
                // NOFOLLOW_LINKS ci-dessous confirme qu'il s'agit bien d'un répertoire réel.
            }
        }
        validateDirectory(directory);
        applyPosixPermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private static void validateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Refus d'un répertoire NEXUS symbolique : " + directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Répertoire NEXUS attendu : " + directory);
        }
    }

    private static void validateRegularFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Refus d'un fichier NEXUS symbolique : " + file);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fichier NEXUS régulier attendu : " + file);
        }
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

    private static void warnIfAclMayBeShared(Path path) {
        AclFileAttributeView view = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }

        try {
            String currentUser = canonicalCurrentUserPrincipal(path);
            List<String> unexpectedPrincipals = new ArrayList<>();
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() != AclEntryType.ALLOW
                        || Collections.disjoint(entry.permissions(), SENSITIVE_ACL_PERMISSIONS)) {
                    continue;
                }
                String principal = entry.principal().getName();
                if (!isTrustedStoragePrincipal(principal, currentUser)) {
                    unexpectedPrincipals.add(principal);
                }
            }

            if (!unexpectedPrincipals.isEmpty()) {
                String principals = String.join(", ", new LinkedHashSet<>(unexpectedPrincipals));
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Le stockage NEXUS peut être accessible à d'autres comptes ({0}) : vérifiez les ACL de {1}",
                        principals,
                        path);
            }
        } catch (IOException | SecurityException inspectionFailure) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Impossible d'inspecter les ACL du stockage NEXUS " + path,
                    inspectionFailure);
        }
    }

    private static String canonicalCurrentUserPrincipal(Path path) throws IOException {
        String currentUser = System.getProperty("user.name", "").trim();
        if (currentUser.isEmpty()) {
            return "";
        }
        return path.getFileSystem()
                .getUserPrincipalLookupService()
                .lookupPrincipalByName(currentUser)
                .getName();
    }

    static boolean isTrustedStoragePrincipal(String principalName, String currentUserPrincipalName) {
        if (principalName == null || principalName.isBlank()) {
            return false;
        }
        String principal = principalName.trim().toUpperCase(Locale.ROOT);
        String currentUser = currentUserPrincipalName == null
                ? ""
                : currentUserPrincipalName.trim().toUpperCase(Locale.ROOT);
        if (!currentUser.isEmpty() && principal.equals(currentUser)) {
            return true;
        }
        return principal.equals("NT AUTHORITY\\SYSTEM")
                || principal.equals("CREATOR OWNER")
                || principal.equals("BUILTIN\\ADMINISTRATORS");
    }
}
