package com.nexus.index.scan;

import com.nexus.index.FileCategory;
import com.nexus.index.FileHasher;
import com.nexus.index.ScannedFile;
import com.nexus.index.SourceLanguage;
import com.nexus.security.ProjectFileLimits;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ProjectScanner {

    /** Compatibilité API : la politique par fichier reste centralisée dans ProjectFileLimits. */
    public static final String MAX_FILE_SIZE_ENVIRONMENT_VARIABLE =
            ProjectFileLimits.MAX_FILE_SIZE_ENVIRONMENT_VARIABLE;
    public static final long DEFAULT_MAX_FILE_SIZE_BYTES =
            ProjectFileLimits.DEFAULT_MAX_FILE_SIZE_BYTES;

    public static final String MAX_FILES_ENVIRONMENT_VARIABLE =
            ProjectScanLimits.MAX_FILES_ENVIRONMENT_VARIABLE;
    public static final String MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE =
            ProjectScanLimits.MAX_TOTAL_BYTES_ENVIRONMENT_VARIABLE;
    public static final int DEFAULT_MAX_FILES = ProjectScanLimits.DEFAULT_MAX_FILES;
    public static final long DEFAULT_MAX_TOTAL_BYTES = ProjectScanLimits.DEFAULT_MAX_TOTAL_BYTES;

    private final long maxFileSizeBytes;
    private final ProjectScanLimits scanLimits;

    public ProjectScanner() {
        this(ProjectFileLimits.maxFileSizeFromEnvironment(), ProjectScanLimits.fromEnvironment());
    }

    public ProjectScanner(long maxFileSizeBytes) {
        this(maxFileSizeBytes, ProjectScanLimits.defaults());
    }

    public ProjectScanner(long maxFileSizeBytes, int maxFiles, long maxTotalBytes) {
        this(maxFileSizeBytes, new ProjectScanLimits(maxFiles, maxTotalBytes));
    }

    private ProjectScanner(long maxFileSizeBytes, ProjectScanLimits scanLimits) {
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maxFileSizeBytes must be greater than zero");
        }
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.scanLimits = scanLimits;
    }

    public long maxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public int maxFiles() {
        return scanLimits.maxFiles();
    }

    public long maxTotalBytes() {
        return scanLimits.maxTotalBytes();
    }

    public List<ScannedFile> scan(Path projectRoot) throws IOException {
        return scanWithDiagnostics(projectRoot).files();
    }

    public ProjectScanResult scanWithDiagnostics(Path projectRoot) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(projectRoot);
        Path root = pathGuard.root();
        List<ScannedFile> files = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int[] skippedFiles = {0};
        int[] visitedEntries = {0};
        long[] consumedBytes = {0L};
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(
                root,
                (ignoreFile, bytes) -> consumeByteBudget(
                        consumedBytes,
                        bytes,
                        "octets de scan (fichiers d'ignore)"));

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(root)) {
                    consumeVisitedEntry(visitedEntries);
                    if (ignoreMatcher.isIgnored(directory, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                ignoreMatcher.registerDirectory(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                consumeVisitedEntry(visitedEntries);
                if (ignoreMatcher.isIgnored(file, false)) {
                    return FileVisitResult.CONTINUE;
                }

                if (!isSupportedTextSource(file)) {
                    return FileVisitResult.CONTINUE;
                }

                String repositoryPath = toRepositoryPath(root.relativize(file));
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    skippedFiles[0]++;
                    diagnostics.add(repositoryPath + " ignoré : lien symbolique interdit");
                    return FileVisitResult.CONTINUE;
                }
                if (!attributes.isRegularFile()) {
                    skippedFiles[0]++;
                    diagnostics.add(repositoryPath + " ignoré : entrée non régulière");
                    return FileVisitResult.CONTINUE;
                }

                Path safeFile;
                try {
                    safeFile = pathGuard.requireRegularFile(file);
                } catch (IOException unsafePath) {
                    skippedFiles[0]++;
                    diagnostics.add(repositoryPath + " ignoré : " + unsafePath.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                BasicFileAttributes safeAttributes = Files.readAttributes(
                        safeFile,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                long size = safeAttributes.size();
                if (size > maxFileSizeBytes) {
                    skippedFiles[0]++;
                    diagnostics.add(repositoryPath + " ignoré : " + size
                            + " octets > limite " + maxFileSizeBytes + " octets");
                    return FileVisitResult.CONTINUE;
                }

                consumeByteBudget(consumedBytes, size, "octets indexables");

                files.add(new ScannedFile(
                        safeFile,
                        repositoryPath,
                        language(safeFile),
                        size,
                        FileHasher.sha256(safeFile, maxFileSizeBytes),
                        safeAttributes.lastModifiedTime().toInstant(),
                        estimateTokens(size),
                        classify(root.relativize(safeFile))));
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(ScannedFile::relativePath));
        diagnostics.sort(String::compareTo);
        return new ProjectScanResult(files, skippedFiles[0], diagnostics);
    }

    private void consumeVisitedEntry(int[] visitedEntries) throws IOException {
        visitedEntries[0]++;
        if (visitedEntries[0] > scanLimits.maxFiles()) {
            throw new IOException("Corpus d'indexation trop volumineux : "
                    + visitedEntries[0] + " entrées visitées > limite " + scanLimits.maxFiles());
        }
    }

    private void consumeByteBudget(long[] consumedBytes, long bytes, String label) throws IOException {
        if (bytes < 0L) {
            throw new IllegalArgumentException("bytes must be positive or zero");
        }
        if (bytes > scanLimits.maxTotalBytes() - consumedBytes[0]) {
            long attempted = consumedBytes[0] > Long.MAX_VALUE - bytes
                    ? Long.MAX_VALUE
                    : consumedBytes[0] + bytes;
            throw new IOException("Corpus d'indexation trop volumineux : "
                    + attempted + " " + label + " > limite "
                    + scanLimits.maxTotalBytes() + " octets");
        }
        consumedBytes[0] += bytes;
    }

    private static boolean isSupportedTextSource(Path file) {
        return SourceLanguage.detect(file).isPresent();
    }

    private static String language(Path file) throws IOException {
        return SourceLanguage.detect(file)
                .map(SourceLanguage::id)
                .orElseThrow(() -> new IOException("Langage source non pris en charge : " + file));
    }

    private static FileCategory classify(Path relativePath) {
        String repositoryPath = toRepositoryPath(relativePath);
        String lowerPath = repositoryPath.toLowerCase(Locale.ROOT);
        String fileName = relativePath.getFileName().toString();
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);

        if (isAgentProfile(lowerPath, lowerFileName)) {
            return FileCategory.AGENT_PROFILE;
        }
        if (isSkillPath(lowerPath)) {
            return FileCategory.SKILL;
        }
        if (isInstructionFile(lowerPath, lowerFileName)) {
            return FileCategory.INSTRUCTION;
        }
        if (lowerFileName.endsWith(".md")) {
            return FileCategory.DOCUMENTATION;
        }
        if (isTestFile(lowerPath, fileName, lowerFileName)) {
            return FileCategory.TEST;
        }
        return FileCategory.SOURCE;
    }

    private static boolean isTestFile(String lowerPath, String fileName, String lowerFileName) {
        String paddedPath = "/" + lowerPath + "/";
        if (paddedPath.contains("/src/test/")
                || paddedPath.contains("/src/it/")
                || paddedPath.contains("/test/")
                || paddedPath.contains("/tests/")
                || paddedPath.contains("/__tests__/")) {
            return true;
        }
        if (fileName.endsWith("Test.java") || fileName.endsWith("Tests.java") || fileName.endsWith("IT.java")) {
            return true;
        }
        if (fileName.endsWith("Test.kt") || fileName.endsWith("Tests.kt")) {
            return true;
        }
        if (lowerFileName.startsWith("test_") && lowerFileName.endsWith(".py")) {
            return true;
        }
        if (lowerFileName.endsWith("_test.py")) {
            return true;
        }
        return lowerFileName.endsWith(".test.ts")
                || lowerFileName.endsWith(".test.tsx")
                || lowerFileName.endsWith(".spec.ts")
                || lowerFileName.endsWith(".spec.tsx")
                || lowerFileName.endsWith(".test.js")
                || lowerFileName.endsWith(".test.jsx")
                || lowerFileName.endsWith(".spec.js")
                || lowerFileName.endsWith(".spec.jsx");
    }

    private static boolean isAgentProfile(String lowerPath, String lowerFileName) {
        return lowerFileName.endsWith(".md")
                && (lowerPath.startsWith(".github/agents/")
                    || lowerPath.startsWith(".claude/agents/"));
    }

    private static boolean isSkillPath(String lowerPath) {
        return lowerPath.startsWith(".github/skills/")
                || lowerPath.startsWith(".claude/skills/")
                || lowerPath.startsWith(".agents/skills/");
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
