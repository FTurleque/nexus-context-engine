package com.nexus.context.source.instruction;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.index.scan.ProjectIgnoreMatcher;
import com.nexus.project.ProjectDescriptor;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
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
        return findNamedFiles(project, names, ContextDiscoveryLimits.defaults().newBudget());
    }

    static List<Path> findNamedFiles(
            ProjectDescriptor project,
            Set<String> names,
            ContextDiscoveryBudget budget) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path root = pathGuard.root();
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root, budget::bytes);
        Set<String> normalizedNames = names.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Path> matches = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                budget.visit(directory);
                if (!directory.equals(root) && ignoreMatcher.isIgnored(directory, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.visit(file);
                if (ignoreMatcher.isIgnored(file, false)
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)
                        || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (normalizedNames.contains(name)) {
                    Path safeFile;
                    try {
                        safeFile = pathGuard.requireRegularFile(file);
                    } catch (IOException unsafePath) {
                        return FileVisitResult.CONTINUE;
                    }
                    budget.candidate(safeFile);
                    matches.add(safeFile);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(path -> repositoryPath(root.relativize(path))));
        return List.copyOf(matches);
    }

    static List<Path> findFilesBelow(ProjectDescriptor project, Path relativeDirectory, String suffix)
            throws IOException {
        return findFilesBelow(
                project,
                relativeDirectory,
                suffix,
                ContextDiscoveryLimits.defaults().newBudget());
    }

    static List<Path> findFilesBelow(
            ProjectDescriptor project,
            Path relativeDirectory,
            String suffix,
            ContextDiscoveryBudget budget) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path root = pathGuard.root();
        Path directory;
        try {
            directory = pathGuard.requireDirectory(pathGuard.resolve(relativeDirectory));
        } catch (IOException missingOrUnsafeDirectory) {
            return List.of();
        }

        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root, budget::bytes);
        registerParentScopes(root, directory.getParent(), ignoreMatcher);
        if (!directory.equals(root) && ignoreMatcher.isIgnored(directory, true)) {
            return List.of();
        }
        ignoreMatcher.registerDirectory(directory);

        List<Path> matches = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                    throws IOException {
                budget.visit(current);
                if (!current.equals(directory) && ignoreMatcher.isIgnored(current, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(current);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.visit(file);
                if (ignoreMatcher.isIgnored(file, false)
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)
                        || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (file.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(suffix.toLowerCase(Locale.ROOT))) {
                    Path safeFile;
                    try {
                        safeFile = pathGuard.requireRegularFile(file);
                    } catch (IOException unsafePath) {
                        return FileVisitResult.CONTINUE;
                    }
                    budget.candidate(safeFile);
                    matches.add(safeFile);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(path -> repositoryPath(root.relativize(path))));
        return List.copyOf(matches);
    }

    static String read(ProjectDescriptor project, Path file) throws IOException {
        return read(project, file, ContextDiscoveryLimits.defaults().newBudget());
    }

    static String read(
            ProjectDescriptor project,
            Path file,
            ContextDiscoveryBudget budget) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path safeFile = pathGuard.requireRegularFile(file);
        return budget.readUtf8NoFollow(safeFile);
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

    private static void registerParentScopes(
            Path root,
            Path targetParent,
            ProjectIgnoreMatcher ignoreMatcher) throws IOException {
        if (targetParent == null || !targetParent.startsWith(root)) {
            return;
        }
        Path current = root;
        for (Path segment : root.relativize(targetParent)) {
            current = current.resolve(segment);
            ignoreMatcher.registerDirectory(current);
        }
    }
}
