package io.github.fturleque.nexus.context.source.instruction;

import io.github.fturleque.nexus.index.scan.ProjectIgnoreMatcher;
import io.github.fturleque.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résout les références @chemin présentes dans les instructions supportées.
 * Les références sont confinées au repository et limitées à cinq niveaux.
 */
final class InstructionReferenceResolver {

    private static final int MAX_DEPTH = 5;
    private static final Pattern REFERENCE = Pattern.compile("(?:^|\\s)@([A-Za-z0-9._/\\\\-]+)");

    List<ResolvedReference> resolve(ProjectDescriptor project, Path instructionFile) throws IOException {
        Path root = project.rootPath().toAbsolutePath().normalize();
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        List<ResolvedReference> resolved = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        visited.add(instructionFile.toAbsolutePath().normalize());
        resolveRecursively(root, instructionFile.toAbsolutePath().normalize(), 1, ignoreMatcher, visited, resolved);
        return List.copyOf(resolved);
    }

    private void resolveRecursively(
            Path root,
            Path sourceFile,
            int depth,
            ProjectIgnoreMatcher ignoreMatcher,
            Set<Path> visited,
            List<ResolvedReference> resolved) throws IOException {
        if (depth > MAX_DEPTH) {
            return;
        }

        String content = InstructionDiscoverySupport.read(sourceFile);
        for (String reference : references(content)) {
            Path target = resolvePath(root, sourceFile.getParent(), reference);
            if (target == null || !visited.add(target) || ignoreMatcher.isIgnored(target, false)) {
                continue;
            }

            String referencedContent = InstructionDiscoverySupport.read(target);
            resolved.add(new ResolvedReference(root.relativize(target), referencedContent, depth));
            resolveRecursively(root, target, depth + 1, ignoreMatcher, visited, resolved);
        }
    }

    private static Path resolvePath(Path root, Path sourceDirectory, String reference) {
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
        if (relativeToSource.startsWith(root) && Files.isRegularFile(relativeToSource)) {
            return relativeToSource;
        }

        Path relativeToRoot = root.resolve(raw).normalize();
        if (relativeToRoot.startsWith(root) && Files.isRegularFile(relativeToRoot)) {
            return relativeToRoot;
        }
        return null;
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
