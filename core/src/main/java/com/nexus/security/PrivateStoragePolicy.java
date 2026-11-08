package com.nexus.security;

import com.nexus.config.NexusPaths;
import com.nexus.config.SecurityPolicy;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Durcissement POSIX/ACL du stockage persistant NEXUS ({@code NEXUS_HOME}, index, verrous).
 *
 * <p>Sur les systèmes sans vue POSIX (notamment Windows), NEXUS conserve les ACL natives
 * héritées du profil utilisateur au lieu de les remplacer de manière destructive. Chaque
 * chemin sensible effectivement créé ou durci est inspecté lorsqu'une vue ACL est disponible,
 * de sorte qu'une ACL explicite plus large sur un enfant ne puisse pas être masquée par une
 * vérification limitée au seul {@code NEXUS_HOME}. Lorsque
 * {@code NEXUS_REQUIRE_PRIVATE_STORAGE=true} ({@link SecurityPolicy#requirePrivateStorage()}),
 * une ACL sensible accordée à un principal inattendu — ou l'impossibilité de démontrer la
 * confidentialité via POSIX/ACL — devient un échec fermé au lieu d'un simple diagnostic.</p>
 */
public final class PrivateStoragePolicy {

    private static final String PATHS_ARGUMENT = "paths";

    private static final System.Logger LOGGER = System.getLogger(PrivateStoragePolicy.class.getName());

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

    private PrivateStoragePolicy() {
    }

    /** Crée les répertoires persistants NEXUS et les rend privés sur les systèmes POSIX. */
    public static void ensurePrivateStorage(NexusPaths paths) throws IOException {
        Objects.requireNonNull(paths, PATHS_ARGUMENT);
        ensurePrivateDirectory(paths, paths.home());
        ensurePrivateDirectory(paths, paths.indexesDirectory());
        ensurePrivateDirectory(paths, paths.locksDirectory());
    }

    /**
     * Crée un répertoire persistant sous {@code NEXUS_HOME} sans suivre de lien symbolique
     * préexistant dans sa hiérarchie relative. Chaque composant enfant est créé et revalidé
     * séparément afin qu'un symlink tel que {@code indexes/<projectId>} ne puisse pas rediriger
     * les écritures Lucene hors du stockage NEXUS.
     */
    public static void ensurePrivateDirectory(NexusPaths paths, Path directory) throws IOException {
        Path home = Objects.requireNonNull(paths, PATHS_ARGUMENT).home();
        Path normalized = requireInsideHome(home, directory);
        ensureHomeDirectory(home);
        if (normalized.equals(home)) {
            return;
        }

        Path current = home;
        for (Path segment : home.relativize(normalized)) {
            current = current.resolve(segment);
            ensurePrivateChildDirectory(current);
            verifyAclPrivacy(current);
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
    public static void ensurePrivateFile(NexusPaths paths, Path file) throws IOException {
        Path normalized = requireInsideHome(Objects.requireNonNull(paths, PATHS_ARGUMENT).home(), file);
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Parent du fichier NEXUS introuvable : " + normalized);
        }
        ensurePrivateDirectory(paths, parent);
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
        verifyAclPrivacy(normalized);
    }

    /** Rend un fichier persistant privé lorsque le système de fichiers expose les permissions POSIX. */
    public static void hardenPrivateFile(NexusPaths paths, Path file) throws IOException {
        Path normalized = requireInsideHome(Objects.requireNonNull(paths, PATHS_ARGUMENT).home(), file);
        validateRegularFile(normalized);
        applyPosixPermissions(normalized, PRIVATE_FILE_PERMISSIONS);
        verifyAclPrivacy(normalized);
    }

    private static void ensureHomeDirectory(Path home) throws IOException {
        if (Files.isSymbolicLink(home)) {
            throw new IOException("Refus d'un répertoire NEXUS symbolique : " + home);
        }
        Files.createDirectories(home);
        validateDirectory(home);
        applyPosixPermissions(home, PRIVATE_DIRECTORY_PERMISSIONS);
        verifyAclPrivacy(home);
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

    private static Path requireInsideHome(Path home, Path path) throws IOException {
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

    private static void verifyAclPrivacy(Path path) throws IOException {
        boolean requirePrivateStorage = SecurityPolicy.requirePrivateStorage();
        AclFileAttributeView view = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            if (requirePrivateStorage
                    && Files.getFileAttributeView(
                            path,
                            PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS) == null) {
                throw new IOException(
                        "Impossible de démontrer la confidentialité du stockage NEXUS pour " + path
                                + " : aucune vue POSIX ni ACL n'est disponible alors que "
                                + NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE + "=true");
            }
            return;
        }

        List<AclEntry> acl;
        try {
            acl = view.getAcl();
        } catch (IOException | SecurityException inspectionFailure) {
            if (requirePrivateStorage) {
                throw new IOException(
                        "Impossible d'inspecter les ACL du stockage NEXUS " + path
                                + " alors que " + NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE + "=true",
                        inspectionFailure);
            }
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Impossible d'inspecter les ACL du stockage NEXUS " + path,
                    inspectionFailure);
            return;
        }

        String currentUser = canonicalCurrentUserPrincipal(path);
        List<String> unexpectedPrincipals = unexpectedStoragePrincipals(acl, currentUser);
        enforceAclPrivacy(path, unexpectedPrincipals, requirePrivateStorage);
    }

    static List<String> unexpectedStoragePrincipals(
            List<AclEntry> entries,
            String currentUserPrincipalName) {
        return unexpectedStoragePrincipals(entries, currentUserPrincipalName, WindowsStoragePrincipals.names());
    }

    static List<String> unexpectedStoragePrincipals(
            List<AclEntry> entries, String currentUserPrincipalName, Set<String> trustedSystemNames) {
        Objects.requireNonNull(entries, "entries");
        LinkedHashSet<String> unexpected = new LinkedHashSet<>();
        for (AclEntry entry : entries) {
            if (entry.type() != AclEntryType.ALLOW
                    || Collections.disjoint(entry.permissions(), SENSITIVE_ACL_PERMISSIONS)) {
                continue;
            }
            String principal = entry.principal().getName();
            if (!isTrustedStoragePrincipal(principal, currentUserPrincipalName, trustedSystemNames)) {
                unexpected.add(principal);
            }
        }
        return List.copyOf(unexpected);
    }

    static void enforceAclPrivacy(
            Path path,
            List<String> unexpectedPrincipals,
            boolean requirePrivateStorage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(unexpectedPrincipals, "unexpectedPrincipals");
        if (unexpectedPrincipals.isEmpty()) {
            return;
        }

        String principals = String.join(", ", unexpectedPrincipals);
        String message = "Le stockage NEXUS peut être accessible à d'autres comptes ("
                + principals + ") : vérifiez les ACL de " + path;
        if (requirePrivateStorage) {
            throw new IOException(
                    message + " ; accès refusé car "
                            + NexusPaths.REQUIRE_PRIVATE_STORAGE_ENVIRONMENT_VARIABLE + "=true");
        }
        LOGGER.log(System.Logger.Level.WARNING, message);
    }

    private static String canonicalCurrentUserPrincipal(Path path) {
        String currentUser = System.getProperty("user.name", "").trim();
        if (currentUser.isEmpty()) {
            return "";
        }
        try {
            return path.getFileSystem()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName(currentUser)
                    .getName();
        } catch (IOException | SecurityException lookupFailure) {
            // Fallback fail-safe: keep inspecting with the raw user name. This may
            // produce a warning for DOMAIN\\user instead of silently skipping the
            // whole ACL inspection, which is the safer failure mode.
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Impossible de canoniser le principal utilisateur courant " + currentUser,
                    lookupFailure);
            return currentUser;
        }
    }

    static boolean isTrustedStoragePrincipal(String principalName, String currentUserPrincipalName) {
        return isTrustedStoragePrincipal(principalName, currentUserPrincipalName, WindowsStoragePrincipals.names());
    }

    static boolean isTrustedStoragePrincipal(String principalName, String currentUserPrincipalName,
            Set<String> trustedSystemNames) {
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
        return trustedSystemNames.contains(principal);
    }
}
