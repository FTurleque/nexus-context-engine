package com.nexus.context.source.git;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fabrique unique des dépôts Git qui alimentent le contexte (BE-0067, complète BE-0035).
 *
 * <p>La découverte remonte depuis la racine réelle du projet jusqu'au premier répertoire qui
 * contient une entrée {@code .git}. Un dépôt trouvé à la racine du projet est retenu sans
 * condition. Un dépôt trouvé dans un répertoire parent n'est retenu que si son répertoire Git
 * appartient à l'utilisateur qui exécute NEXUS, à l'image de {@code safe.directory} de Git ;
 * sinon, ou si le propriétaire n'est pas déterminable, l'ouverture échoue par
 * {@link RejectedParentRepositoryException} avant toute lecture de la configuration du dépôt.
 * Après ouverture, le propriétaire du répertoire Git et du répertoire commun retenus est
 * revérifié ; s'il ne correspond plus, le dépôt est fermé et écarté avec le même diagnostic.
 * La remontée s'arrête au premier {@code .git} rencontré : un dépôt invalide ne fait pas
 * chercher plus haut.</p>
 */
final class GitRepositoryLocator {

    /** Diagnostic public stable d'un dépôt parent écarté : ni chemin absolu ni nom de compte. */
    static final String PARENT_REPOSITORY_REJECTED =
            "contexte Git ignoré : dépôt Git parent n'appartenant pas à l'utilisateur courant";

    /** Propriétaire d'un répertoire Git au regard de l'utilisateur courant. */
    enum Ownership {
        CURRENT_USER,
        OTHER_ACCOUNT,
        UNDETERMINED
    }

    /** Résolution du propriétaire, injectable pour simuler un système de fichiers sans propriétaire. */
    @FunctionalInterface
    interface OwnershipResolver {
        Ownership ownership(Path gitEntry);
    }

    /** Dépôt parent écarté ; le message est {@link #PARENT_REPOSITORY_REJECTED}, sans chemin. */
    static final class RejectedParentRepositoryException extends IOException {
        private static final long serialVersionUID = 1L;

        RejectedParentRepositoryException() {
            super(PARENT_REPOSITORY_REJECTED);
        }
    }

    private static final GitRepositoryLocator SYSTEM = new GitRepositoryLocator(new FileOwnershipResolver());

    private final OwnershipResolver ownershipResolver;

    GitRepositoryLocator(OwnershipResolver ownershipResolver) {
        this.ownershipResolver = Objects.requireNonNull(ownershipResolver, "ownershipResolver");
    }

    /** Fabrique de production, fondée sur le propriétaire déclaré par le système de fichiers. */
    static GitRepositoryLocator system() {
        return SYSTEM;
    }

    /**
     * Ouvre le dépôt Git du projet.
     *
     * @throws RepositoryNotFoundException aucun dépôt à la racine ni dans un parent
     * @throws RejectedParentRepositoryException dépôt parent n'appartenant pas à l'utilisateur courant
     */
    Repository open(Path projectRoot) throws IOException {
        Path root = projectRoot.toRealPath();
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            Path dotGit = directory.resolve(Constants.DOT_GIT);
            if (Files.exists(dotGit, LinkOption.NOFOLLOW_LINKS)) {
                return open(directory, dotGit, !directory.equals(root));
            }
        }
        throw new RepositoryNotFoundException(root.toFile());
    }

    private Repository open(Path directory, Path dotGit, boolean parentRepository) throws IOException {
        if (parentRepository) {
            requireCurrentUser(dotGit);
        }
        Path gitDir = dotGit;
        if (!Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
            // Fichier gitlink (sous-module, worktree lié) : seule sa cible est lue ici. Le
            // worktree reste déduit du dépôt cible, comme le faisait findGitDir.
            gitDir = resolveGitLink(directory);
            if (parentRepository) {
                requireCurrentUser(gitDir);
            }
        }
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setMustExist(true)
                .build();
        if (parentRepository) {
            try {
                requireCurrentUserOfOpenedRepository(repository);
            } catch (RejectedParentRepositoryException rejected) {
                repository.close();
                throw rejected;
            }
        }
        return repository;
    }

    /**
     * Revérifie, après ouverture, le propriétaire du répertoire Git et du répertoire commun
     * retenus par JGit : un remplacement entre le contrôle initial et l'ouverture écarte le dépôt.
     */
    private void requireCurrentUserOfOpenedRepository(Repository repository)
            throws RejectedParentRepositoryException {
        Path directory = repository.getDirectory().toPath();
        requireCurrentUser(directory);
        File common = repository.getCommonDirectory();
        if (common != null && !common.toPath().equals(directory)) {
            requireCurrentUser(common.toPath());
        }
    }

    private static Path resolveGitLink(Path directory) throws IOException {
        FileRepositoryBuilder resolver = new FileRepositoryBuilder().setWorkTree(directory.toFile());
        try {
            resolver.setup();
        } catch (IllegalArgumentException invalidGitLink) {
            throw new IOException("Fichier gitlink invalide", invalidGitLink);
        }
        if (resolver.getGitDir() == null) {
            throw new RepositoryNotFoundException(directory.toFile());
        }
        return resolver.getGitDir().toPath();
    }

    private void requireCurrentUser(Path gitEntry) throws RejectedParentRepositoryException {
        if (ownershipResolver.ownership(gitEntry) != Ownership.CURRENT_USER) {
            throw new RejectedParentRepositoryException();
        }
    }

    /**
     * Propriétaire déclaré par le système de fichiers. L'utilisateur courant est reconnu par son
     * nom de compte et par le propriétaire attribué à un fichier qu'il crée : sous Windows, un
     * processus élevé attribue ses fichiers au groupe Administrateurs, comme le tolère Git.
     */
    static final class FileOwnershipResolver implements OwnershipResolver {
        private volatile Set<UserPrincipal> currentPrincipals;

        @Override
        public Ownership ownership(Path gitEntry) {
            UserPrincipal owner;
            try {
                owner = Files.getOwner(gitEntry, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | UnsupportedOperationException | SecurityException undetermined) {
                return Ownership.UNDETERMINED;
            }
            Set<UserPrincipal> current = currentPrincipals(gitEntry.getFileSystem());
            if (owner == null || current.isEmpty()) {
                return Ownership.UNDETERMINED;
            }
            return current.contains(owner) ? Ownership.CURRENT_USER : Ownership.OTHER_ACCOUNT;
        }

        private Set<UserPrincipal> currentPrincipals(FileSystem fileSystem) {
            Set<UserPrincipal> principals = currentPrincipals;
            if (principals == null) {
                principals = resolveCurrentPrincipals(fileSystem);
                currentPrincipals = principals;
            }
            return principals;
        }

        private static Set<UserPrincipal> resolveCurrentPrincipals(FileSystem fileSystem) {
            Set<UserPrincipal> principals = new LinkedHashSet<>();
            UserPrincipal namedUser = null;
            String userName = System.getProperty("user.name");
            if (userName != null && !userName.isBlank()) {
                try {
                    namedUser = fileSystem.getUserPrincipalLookupService().lookupPrincipalByName(userName);
                    principals.add(namedUser);
                } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                    // Compte introuvable par son nom : seul le propriétaire du fichier sonde reste.
                }
            }
            Optional<FileAttribute<?>> ownerOnly = ownerOnlyAttribute(fileSystem, namedUser);
            if (ownerOnly.isEmpty()) {
                return Set.copyOf(principals);
            }
            Path probe = null;
            try {
                probe = Files.createTempFile("nexus-owner-", ".probe", ownerOnly.get());
                principals.add(Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                // Sonde impossible : seul le compte résolu par son nom reste.
            } finally {
                if (probe != null) {
                    try {
                        Files.deleteIfExists(probe);
                    } catch (IOException ignored) {
                        // Le fichier sonde est vide et temporaire.
                    }
                }
            }
            return Set.copyOf(principals);
        }

        /**
         * Permissions de création du fichier sonde, réservées à son propriétaire : POSIX
         * {@code rw-------}, ou ACL limitée au compte courant. Sans l'un ni l'autre, aucune sonde
         * n'est créée dans le répertoire temporaire.
         */
        private static Optional<FileAttribute<?>> ownerOnlyAttribute(FileSystem fileSystem, UserPrincipal namedUser) {
            Set<String> views = fileSystem.supportedFileAttributeViews();
            if (views.contains("posix")) {
                return Optional.of(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            if (views.contains("acl") && namedUser != null) {
                AclEntry entry = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(namedUser)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                List<AclEntry> acl = List.of(entry);
                return Optional.of(new FileAttribute<List<AclEntry>>() {
                    @Override
                    public String name() {
                        return "acl:acl";
                    }

                    @Override
                    public List<AclEntry> value() {
                        return acl;
                    }
                });
            }
            return Optional.empty();
        }
    }
}
