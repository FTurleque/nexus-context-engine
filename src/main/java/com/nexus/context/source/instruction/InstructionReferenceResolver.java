package com.nexus.context.source.instruction;

import com.nexus.index.scan.ProjectIgnoreMatcher;
import com.nexus.project.ProjectDescriptor;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résout les références @chemin présentes dans les instructions supportées.
 * Les références sont confinées au repository, refusent les liens symboliques
 * et sont limitées à cinq niveaux.
 */
final class InstructionReferenceResolver {

    private static final int MAX_DEPTH = 5;
    private static final Pattern REFERENCE = Pattern.compile("(?:^|\\s)@([A-Za-z0-9._/\\\\-]+)");

    List<ResolvedReference> resolve(ProjectDescriptor project, Path instructionFile) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path root = pathGuard.root();
        Path safeInstructionFile = pathGuard.requireRegularFile(instructionFile);
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        List<ResolvedReference> resolved = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        visited.add(safeInstructionFile);
        resolveRecursively(
                project,
                pathGuard,
                safeInstructionFile,
                1,
                ignoreMatcher,
                visited,
                resolved);
        return List.copyOf(resolved);
    }

    private void resolveRecursively(
            ProjectDescriptor project,
            ProjectPathGuard pathGuard,
            Path sourceFile,
            int depth,
            ProjectIgnoreMatcher ignoreMatcher,
            Set<Path> visited,
            List<ResolvedReference> resolved) throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }

        String content = InstructionDiscoverySupport.read(project, sourceFile);
        for (String reference : references(content)) {
            Path target = resolvePath(pathGuard, sourceFile.getParent(), reference);
            if (target == null || !visited.add(target)) {
                continue;
            }
            registerIgnoreScopes(pathGuard.root(), target.getParent(), ignoreMatcher);
            if (ignoreMatcher.isIgnored(target, false)) {
                continue;
            }

            String referencedContent = InstructionDiscoverySupport.read(project, target);
            resolved.add(new ResolvedReference(pathGuard.root().relativize(target), referencedContent, depth));
            resolveRecursively(
                    project,
                    pathGuard,
                    target,
                    depth + 1,
                    ignoreMatcher,
                    visited,
                    resolved);
        }
    }

    private static void registerIgnoreScopes(
            Path root,
            Path targetDirectory,
            ProjectIgnoreMatcher ignoreMatcher) throws IOException {
        if (targetDirectory == null || !targetDirectory.startsWith(root)) {
            return;
        }
        Path current = root;
        Path relative = root.relativize(targetDirectory);
        for (Path segment : relative) {
            current = current.resolve(segment);
            ignoreMatcher.registerDirectory(current);
        }
    }

    private static Path resolvePath(ProjectPathGuard pathGuard, Path sourceDirectory, String reference) {
        if (reference.isBlank() || reference.startsWith("~")) {
            return null;
        }

        Path raw;
        try {
            raw = Path.of(reference);
        } catch (RuntimeException invalidPath) {
            return null;
        }
        if (raw.isAbsolute()) {
            return null;
        }

        Path relativeToSource = sourceDirectory.resolve(raw).normalize();
        Path safe = safeRegularFile(pathGuard, relativeToSource);
        if (safe != null) {
            return safe;
        }

        Path relativeToRoot = pathGuard.root().resolve(raw).normalize();
        return safeRegularFile(pathGuard, relativeToRoot);
    }

    private static Path safeRegularFile(ProjectPathGuard pathGuard, Path candidate) {
        try {
            return pathGuard.requireRegularFile(candidate);
        } catch (IOException unsafeOrMissing) {
            return null;
        }
    }

    private static List<String> references(String content) {
        List<String> references = new ArrayList<>();
        boolean fenced = false;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher matcher = REFERENCE.matcher(line);
            while (matcher.find()) {
                references.add(matcher.group(1));
            }
        }
        return List.copyOf(references);
    }

    record ResolvedReference(Path relativePath, String content, int depth) {
    }
}
