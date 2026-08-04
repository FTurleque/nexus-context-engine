package com.nexus.context.source.instruction;

import com.nexus.index.scan.ProjectIgnoreMatcher;
import com.nexus.project.ProjectDescriptor;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class InstructionDiscoverySupport {

    private InstructionDiscoverySupport() {
    }

    static List<Path> findNamedFiles(ProjectDescriptor project, Set<String> names) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path root = pathGuard.root();
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        Set<String> normalizedNames = names.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Path> matches = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(root) && ignoreMatcher.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (ignoreMatcher.isIgnored(file, false)
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)
                        || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (normalizedNames.contains(name)) {
                    try {
                        matches.add(pathGuard.requireRegularFile(file));
                    } catch (IOException unsafePath) {
                        // Une entrée devenue non sûre pendant le scan n'est jamais exposée au provider.
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(path -> repositoryPath(root.relativize(path))));
        return List.copyOf(matches);
    }

    static List<Path> findFilesBelow(ProjectDescriptor project, Path relativeDirectory, String suffix)
            throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path root = pathGuard.root();
        Path directory;
        try {
            directory = pathGuard.requireDirectory(pathGuard.resolve(relativeDirectory));
        } catch (IOException missingOrUnsafeDirectory) {
            return List.of();
        }

        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        List<Path> matches = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                    throws IOException {
                if (!current.equals(directory) && ignoreMatcher.isIgnored(current, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(current);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (ignoreMatcher.isIgnored(file, false)
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)
                        || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (file.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(suffix.toLowerCase(Locale.ROOT))) {
                    try {
                        matches.add(pathGuard.requireRegularFile(file));
                    } catch (IOException unsafePath) {
                        // Ignore une entrée remplacée par un lien ou sortie de la frontière entre-temps.
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(path -> repositoryPath(root.relativize(path))));
        return List.copyOf(matches);
    }

    static String read(ProjectDescriptor project, Path file) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path safeFile = pathGuard.requireRegularFile(file);
        return Files.readString(safeFile, StandardCharsets.UTF_8);
    }

    static Path relative(ProjectDescriptor project, Path absolutePath) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path safePath = pathGuard.requireRegularFile(absolutePath);
        return pathGuard.root().relativize(safePath);
    }

    static boolean directoryScopeApplies(Path instructionRelativePath, List<Path> targetPaths) {
        Path parent = instructionRelativePath.getParent();
        if (parent == null || parent.getNameCount() == 0) {
            return true;
        }
        return targetPaths.stream().anyMatch(target -> target.normalize().startsWith(parent.normalize()));
    }

    static int directoryDepth(Path instructionRelativePath) {
        Path parent = instructionRelativePath.getParent();
        return parent == null ? 0 : parent.getNameCount();
    }

    static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
