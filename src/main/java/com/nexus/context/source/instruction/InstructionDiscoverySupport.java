package com.nexus.context.source.instruction;

import com.nexus.index.scan.ProjectIgnoreMatcher;
import com.nexus.project.ProjectDescriptor;

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
        Path root = project.rootPath().toAbsolutePath().normalize();
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
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (ignoreMatcher.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (normalizedNames.contains(name)) {
                    matches.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(path -> repositoryPath(root.relativize(path))));
        return List.copyOf(matches);
    }

    static List<Path> findFilesBelow(ProjectDescriptor project, Path relativeDirectory, String suffix)
            throws IOException {
        Path root = project.rootPath().toAbsolutePath().normalize();
        Path directory = root.resolve(relativeDirectory).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            return List.of();
        }

        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        List<Path> matches = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                    throws IOException {
                if (!current.equals(root) && ignoreMatcher.isIgnored(current, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                ignoreMatcher.registerDirectory(current);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!ignoreMatcher.isIgnored(file, false)
                        && file.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(suffix.toLowerCase(Locale.ROOT))) {
                    matches.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        matches.sort(Comparator.comparing(path -> repositoryPath(root.relativize(path))));
        return List.copyOf(matches);
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static Path relative(ProjectDescriptor project, Path absolutePath) {
        return project.rootPath().toAbsolutePath().normalize().relativize(absolutePath.toAbsolutePath().normalize());
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
