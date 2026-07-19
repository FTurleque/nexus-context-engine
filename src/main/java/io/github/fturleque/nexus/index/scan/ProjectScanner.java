package io.github.fturleque.nexus.index.scan;

import io.github.fturleque.nexus.index.FileCategory;
import io.github.fturleque.nexus.index.FileHasher;
import io.github.fturleque.nexus.index.ScannedFile;

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

public final class ProjectScanner {

    public List<ScannedFile> scan(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Le chemin du projet n'est pas un répertoire : " + root);
        }

        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        List<ScannedFile> files = new ArrayList<>();

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
                if (ignoreMatcher.isIgnored(file, false) || !isSupportedTextSource(file)) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = root.relativize(file);
                files.add(new ScannedFile(
                        file,
                        toRepositoryPath(relative),
                        language(file),
                        attributes.size(),
                        FileHasher.sha256(file),
                        attributes.lastModifiedTime().toInstant(),
                        estimateTokens(attributes.size()),
                        classify(relative)));
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(ScannedFile::relativePath));
        return List.copyOf(files);
    }

    private static boolean isSupportedTextSource(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java") || fileName.endsWith(".md");
    }

    private static String language(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java") ? "java" : "markdown";
    }

    private static FileCategory classify(Path relativePath) {
        String repositoryPath = toRepositoryPath(relativePath);
        String lowerPath = repositoryPath.toLowerCase(Locale.ROOT);
        String fileName = relativePath.getFileName().toString();
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);

        if (isInstructionFile(lowerPath, lowerFileName)) {
            return FileCategory.INSTRUCTION;
        }
        if (lowerFileName.endsWith(".md")) {
            return FileCategory.DOCUMENTATION;
        }

        String paddedPath = "/" + lowerPath + "/";
        if (paddedPath.contains("/src/test/")
                || paddedPath.contains("/src/it/")
                || fileName.endsWith("Test.java")
                || fileName.endsWith("Tests.java")
                || fileName.endsWith("IT.java")) {
            return FileCategory.TEST;
        }
        return FileCategory.SOURCE;
    }

    private static boolean isInstructionFile(String lowerPath, String lowerFileName) {
        return lowerFileName.equals("agents.md")
                || lowerFileName.equals("agent.md")
                || lowerFileName.equals("claude.md")
                || lowerFileName.equals("gemini.md")
                || lowerPath.equals(".github/copilot-instructions.md")
                || (lowerPath.startsWith(".github/instructions/")
                    && lowerFileName.endsWith(".instructions.md"));
    }

    private static int estimateTokens(long sizeBytes) {
        long estimate = Math.max(1L, (sizeBytes + 3L) / 4L);
        return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    private static String toRepositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
