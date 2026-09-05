package com.nexus.index.scan;

import com.nexus.security.ProjectPathGuard;
import com.nexus.security.SafeFileIO;
import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Résout les exclusions NEXUS en combinant les règles .gitignore,
 * .nexusignore et une liste conservatrice d'exclusions intégrées.
 *
 * <p>Les fichiers d'ignore sont des entrées de repository non fiables au même
 * titre que les sources. Leur lecture est donc bornée individuellement et
 * cumulativement, puis débitée d'un budget externe lorsqu'un appelant fournit
 * un budget global de scan/découverte.</p>
 */
public final class ProjectIgnoreMatcher {

    /** Borne physique stricte d'un fichier .gitignore/.nexusignore. */
    public static final long MAX_IGNORE_FILE_BYTES = 1024L * 1024L;
    /** Borne cumulée stricte de tous les fichiers d'ignore chargés par un matcher. */
    public static final long MAX_IGNORE_TOTAL_BYTES = 8L * 1024L * 1024L;

    private static final Set<String> BUILT_IN_DIRECTORIES = Set.of(
            ".git",
            ".idea",
            ".gradle",
            ".nexus",
            ".aws",
            ".ssh",
            ".gnupg",
            ".kube",
            ".secrets",
            "target",
            "build",
            "out",
            "node_modules",
            "dist",
            "coverage");

    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".env",
            ".npmrc",
            ".pypirc",
            ".netrc",
            ".git-credentials",
            "id_rsa",
            "id_ed25519",
            "credentials",
            "credentials.json",
            "secrets.json",
            "secrets.yml",
            "secrets.yaml",
            "application-secrets.properties");

    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            ".pem",
            ".key",
            ".p12",
            ".pfx",
            ".jks",
            ".keystore",
            ".kdbx");

    private static final IgnoreFileBudget NO_EXTERNAL_BUDGET = (file, bytes) -> { };

    private final ProjectPathGuard pathGuard;
    private final Path projectRoot;
    private final List<ScopedIgnoreNode> gitScopes = new ArrayList<>();
    private final List<ScopedIgnoreNode> nexusScopes = new ArrayList<>();
    private final Set<Path> loadedDirectories = new HashSet<>();
    private final long maxIgnoreFileBytes;
    private final long maxIgnoreTotalBytes;
    private final IgnoreFileBudget externalBudget;
    private long consumedIgnoreBytes;

    public ProjectIgnoreMatcher(Path projectRoot) throws IOException {
        this(projectRoot, MAX_IGNORE_FILE_BYTES, MAX_IGNORE_TOTAL_BYTES, NO_EXTERNAL_BUDGET);
    }

    /**
     * Crée un matcher dont toutes les lectures de fichiers d'ignore sont aussi
     * débitées du budget global fourni par l'appelant.
     */
    public ProjectIgnoreMatcher(Path projectRoot, IgnoreFileBudget externalBudget) throws IOException {
        this(projectRoot, MAX_IGNORE_FILE_BYTES, MAX_IGNORE_TOTAL_BYTES, externalBudget);
    }

    ProjectIgnoreMatcher(
            Path projectRoot,
            long maxIgnoreFileBytes,
            long maxIgnoreTotalBytes,
            IgnoreFileBudget externalBudget) throws IOException {
        if (maxIgnoreFileBytes <= 0L) {
            throw new IllegalArgumentException("maxIgnoreFileBytes must be greater than zero");
        }
        if (maxIgnoreTotalBytes <= 0L) {
            throw new IllegalArgumentException("maxIgnoreTotalBytes must be greater than zero");
        }
        this.pathGuard = new ProjectPathGuard(projectRoot);
        this.projectRoot = pathGuard.root();
        this.maxIgnoreFileBytes = maxIgnoreFileBytes;
        this.maxIgnoreTotalBytes = maxIgnoreTotalBytes;
        this.externalBudget = Objects.requireNonNull(externalBudget, "externalBudget");
        registerDirectory(this.projectRoot);
    }

    public void registerDirectory(Path directory) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(projectRoot) || !loadedDirectories.add(normalizedDirectory)) {
            return;
        }
        Path safeDirectory = pathGuard.requireDirectory(normalizedDirectory);
        loadIgnoreFile(safeDirectory, ".gitignore", gitScopes);
        loadIgnoreFile(safeDirectory, ".nexusignore", nexusScopes);
    }

    public boolean isIgnored(Path path, boolean directory) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Le chemin n'appartient pas au projet : " + normalizedPath);
        }

        Path relativePath = projectRoot.relativize(normalizedPath);
        if (relativePath.getNameCount() == 0) {
            return false;
        }

        if (isBuiltInExcluded(relativePath, directory)) {
            return true;
        }

        return evaluate(gitScopes, normalizedPath, directory)
                || evaluate(nexusScopes, normalizedPath, directory);
    }

    private void loadIgnoreFile(
            Path directory,
            String fileName,
            List<ScopedIgnoreNode> scopes) throws IOException {
        Path ignoreFile = directory.resolve(fileName);
        if (Files.isSymbolicLink(ignoreFile)
                || !Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Path safeIgnoreFile = pathGuard.requireRegularFile(ignoreFile);
        BasicFileAttributes attributes = Files.readAttributes(
                safeIgnoreFile,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Fichier d'ignore devenu non régulier avant lecture : " + safeIgnoreFile);
        }
        long declaredSize = attributes.size();
        if (declaredSize > maxIgnoreFileBytes) {
            throw new IOException(
                    "Fichier d'ignore trop volumineux : " + safeIgnoreFile
                            + " (maximum " + maxIgnoreFileBytes + " octets)");
        }

        // Réserve le budget avant ouverture : un budget global volontairement
        // petit ne doit jamais permettre jusqu'à 1 MiB d'I/O avant son rejet.
        // La lecture est ensuite bornée à la taille observée ; si le fichier
        // grandit entre les deux opérations, SafeFileIO échoue au premier octet
        // supplémentaire au lieu de sous-compter silencieusement la mutation.
        consumeIgnoreBytes(safeIgnoreFile, declaredSize);
        long readLimit = Math.max(1L, declaredSize);
        byte[] content = SafeFileIO.readBytesNoFollow(safeIgnoreFile, readLimit);

        IgnoreNode node = new IgnoreNode();
        try (InputStream input = new ByteArrayInputStream(content)) {
            node.parse(input);
        }
        scopes.add(new ScopedIgnoreNode(directory, node));
    }

    private void consumeIgnoreBytes(Path file, long bytes) throws IOException {
        if (bytes > maxIgnoreTotalBytes - consumedIgnoreBytes) {
            long attempted = consumedIgnoreBytes > Long.MAX_VALUE - bytes
                    ? Long.MAX_VALUE
                    : consumedIgnoreBytes + bytes;
            throw new IOException(
                    "Budget cumulé des fichiers d'ignore dépassé : " + attempted
                            + " octets > limite " + maxIgnoreTotalBytes + " octets");
        }
        externalBudget.consume(file, bytes);
        consumedIgnoreBytes += bytes;
    }

    private static boolean evaluate(List<ScopedIgnoreNode> scopes, Path path, boolean directory) {
        Boolean decision = null;
        for (ScopedIgnoreNode scope : scopes) {
            if (!path.startsWith(scope.baseDirectory())) {
                continue;
            }
            Path relativeToScope = scope.baseDirectory().relativize(path);
            if (relativeToScope.getNameCount() == 0) {
                continue;
            }
            Boolean scopedDecision = scope.node().checkIgnored(toGitPath(relativeToScope), directory);
            if (scopedDecision != null) {
                decision = scopedDecision;
            }
        }
        return Boolean.TRUE.equals(decision);
    }

    private static boolean isBuiltInExcluded(Path relativePath, boolean directory) {
        for (Path segment : relativePath) {
            if (BUILT_IN_DIRECTORIES.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        if (directory) {
            return false;
        }

        String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SENSITIVE_FILE_NAMES.contains(fileName) || fileName.startsWith(".env.")) {
            return true;
        }
        return SENSITIVE_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    private static String toGitPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    @FunctionalInterface
    public interface IgnoreFileBudget {
        void consume(Path file, long bytes) throws IOException;
    }

    private record ScopedIgnoreNode(Path baseDirectory, IgnoreNode node) {
    }
}
