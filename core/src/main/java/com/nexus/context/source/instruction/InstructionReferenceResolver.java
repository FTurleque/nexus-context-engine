package com.nexus.context.source.instruction;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
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
 * et sont limitées à cinq niveaux ainsi que par le budget global de découverte.
 */
final class InstructionReferenceResolver {

    private static final int MAX_DEPTH = 5;
    private static final Pattern REFERENCE = Pattern.compile("(?:^|\\s)@([A-Za-z0-9._/\\\\-]+)");

    List<ResolvedReference> resolve(ProjectDescriptor project, Path instructionFile) throws IOException {
        ContextDiscoveryBudget budget = ContextDiscoveryLimits.defaults().newBudget();
        String content = InstructionDiscoverySupport.read(project, instructionFile, budget);
        return resolve(project, instructionFile, content, budget);
    }

    List<ResolvedReference> resolve(
            ProjectDescriptor project,
            Path instructionFile,
            String instructionContent,
            ContextDiscoveryBudget budget) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Path root = pathGuard.root();
        Path safeInstructionFile = pathGuard.requireRegularFile(instructionFile);
        ProjectIgnoreMatcher ignoreMatcher = new ProjectIgnoreMatcher(root);
        List<ResolvedReference> resolved = new ArrayList<>();
        Set<Path> visited = new LinkedHashSet<>();
        visited.add(safeInstructionFile);
        ResolutionState state = new ResolutionState(pathGuard, ignoreMatcher, visited, resolved, budget);
        resolveRecursively(project, state, safeInstructionFile, instructionContent, 1);
        return List.copyOf(resolved);
    }

    private void resolveRecursively(
            ProjectDescriptor project,
            ResolutionState state,
            Path sourceFile,
            String sourceContent,
            int depth) throws IOException {
        state.budget().checkpoint();
        if (depth > MAX_DEPTH) {
            return;
        }

        visitReferences(sourceContent, reference -> {
            Path target = resolvePath(state.pathGuard(), sourceFile.getParent(), reference);
            if (target != null && state.visited().add(target)) {
                state.budget().visit(target);
                registerIgnoreScopes(state.pathGuard().root(), target.getParent(), state.ignoreMatcher());
                if (!state.ignoreMatcher().isIgnored(target, false)) {
                    state.budget().candidate(target);
                    String referencedContent = InstructionDiscoverySupport.read(project, target, state.budget());
                    state.resolved().add(new ResolvedReference(
                            state.pathGuard().root().relativize(target), referencedContent, depth));
                    resolveRecursively(project, state, target, referencedContent, depth + 1);
                }
            }
        });
    }

    private record ResolutionState(
            ProjectPathGuard pathGuard,
            ProjectIgnoreMatcher ignoreMatcher,
            Set<Path> visited,
            List<ResolvedReference> resolved,
            ContextDiscoveryBudget budget) {
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

    /**
     * Visite les références au fil du scan sans matérialiser une liste intermédiaire.
     * La mémoire temporaire reste ainsi indépendante du nombre de références présentes
     * dans un fichier borné en octets mais potentiellement très dense en objets logiques.
     */
    private static void visitReferences(String content, ReferenceVisitor visitor) throws IOException {
        Matcher matcher = REFERENCE.matcher(content);
        boolean fenced = false;
        int lineStart = 0;
        int length = content.length();

        for (int index = 0; index <= length; index++) {
            boolean endOfContent = index == length;
            char character = endOfContent ? '\0' : content.charAt(index);
            if (!endOfContent && character != '\n' && character != '\r') {
                continue;
            }

            int lineEnd = index;
            int trimmedStart = lineStart;
            while (trimmedStart < lineEnd && Character.isWhitespace(content.charAt(trimmedStart))) {
                trimmedStart++;
            }

            if (startsFence(content, trimmedStart, lineEnd)) {
                fenced = !fenced;
            } else if (!fenced && lineStart < lineEnd) {
                matcher.region(lineStart, lineEnd);
                while (matcher.find()) {
                    visitor.visit(matcher.group(1));
                }
            }

            if (!endOfContent
                    && character == '\r'
                    && index + 1 < length
                    && content.charAt(index + 1) == '\n') {
                index++;
            }
            lineStart = index + 1;
        }
    }

    private static boolean startsFence(String content, int start, int end) {
        if (end - start < 3) {
            return false;
        }
        char marker = content.charAt(start);
        return (marker == '`' || marker == '~')
                && content.charAt(start + 1) == marker
                && content.charAt(start + 2) == marker;
    }

    @FunctionalInterface
    private interface ReferenceVisitor {
        void visit(String reference) throws IOException;
    }

    record ResolvedReference(Path relativePath, String content, int depth) {
    }
}
