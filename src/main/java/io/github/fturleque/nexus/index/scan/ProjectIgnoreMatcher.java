package io.github.fturleque.nexus.index.scan;

import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Résout les exclusions NEXUS en combinant les règles .gitignore,
 * .nexusignore et une liste conservatrice d'exclusions intégrées.
 */
public final class ProjectIgnoreMatcher {

    private static final Set<String> BUILT_IN_DIRECTORIES = Set.of(
            ".git",
            ".idea",
            ".gradle",
            ".nexus",
            "target",
            "build",
            "out",
            "node_modules",
            "dist",
            "coverage");

    private static final Set<String> SENSITIVE_FILE_NAMES = Set.of(
            ".env",
            "id_rsa",
            "id_ed25519",
            "credentials.json",
            "secrets.json");

    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            ".pem",
            ".key",
            ".p12",
            ".pfx");

    private final Path projectRoot;
    private final List<ScopedIgnoreNode> gitScopes = new ArrayList<>();
    private final List<ScopedIgnoreNode> nexusScopes = new ArrayList<>();
    private final Set<Path> loadedDirectories = new HashSet<>();

    public ProjectIgnoreMatcher(Path projectRoot) throws IOException {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        registerDirectory(this.projectRoot);
    }

    public void registerDirectory(Path directory) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(projectRoot) || !loadedDirectories.add(normalizedDirectory)) {
            return;
        }
        loadIgnoreFile(normalizedDirectory, ".gitignore", gitScopes);
        loadIgnoreFile(normalizedDirectory, ".nexusignore", nexusScopes);
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
        if (!Files.isRegularFile(ignoreFile)) {
            return;
        }

        IgnoreNode node = new IgnoreNode();
        try (InputStream input = Files.newInputStream(ignoreFile)) {
            node.parse(input);
        }
        scopes.add(new ScopedIgnoreNode(directory, node));
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

    private record ScopedIgnoreNode(Path baseDirectory, IgnoreNode node) {
    }
}
