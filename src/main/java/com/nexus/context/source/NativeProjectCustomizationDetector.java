package com.nexus.context.source;

import com.nexus.project.ProjectDescriptor;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Détecte les personnalisations natives qui ne doivent pas être injectées
 * automatiquement dans le ContextBundle.
 */
public final class NativeProjectCustomizationDetector {

    public Map<String, List<String>> detect(ProjectDescriptor project) throws IOException {
        return detect(project, ContextDiscoveryLimits.defaults().newBudget());
    }

    public Map<String, List<String>> detect(
            ProjectDescriptor project,
            ContextDiscoveryBudget budget) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(project.rootPath());
        Map<String, List<String>> detected = new LinkedHashMap<>();

        List<String> operational = existing(pathGuard, List.of(
                ".claude/settings.json",
                ".claude/settings.local.json",
                ".mcp.json",
                "mcp.json",
                ".gemini/settings.json"), budget);
        putIfNotEmpty(detected, "operationalConfigurations", operational);

        List<String> agentProfiles = new ArrayList<>();
        agentProfiles.addAll(findBelow(pathGuard, ".github/agents", ".md", budget));
        agentProfiles.addAll(findBelow(pathGuard, ".claude/agents", ".md", budget));
        putIfNotEmpty(detected, "agentProfiles", agentProfiles);

        List<String> hooks = new ArrayList<>();
        hooks.addAll(findBelow(pathGuard, ".github/hooks", ".json", budget));
        hooks.addAll(findBelow(pathGuard, ".claude/hooks", ".json", budget));
        putIfNotEmpty(detected, "hooks", hooks);

        return Map.copyOf(detected);
    }

    private static List<String> existing(
            ProjectPathGuard pathGuard,
            List<String> relativePaths,
            ContextDiscoveryBudget budget) throws IOException {
        List<String> found = new ArrayList<>();
        for (String relative : relativePaths) {
            budget.checkpoint();
            Path candidate = pathGuard.resolve(Path.of(relative));
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
                continue;
            }
            budget.visit(candidate);
            Path safeFile;
            try {
                safeFile = pathGuard.requireRegularFile(candidate);
            } catch (IOException unsafeEntry) {
                continue;
            }
            budget.candidate(safeFile);
            found.add(relative);
        }
        return List.copyOf(found);
    }

    private static List<String> findBelow(
            ProjectPathGuard pathGuard,
            String relativeDirectory,
            String suffix,
            ContextDiscoveryBudget budget) throws IOException {
        Path root = pathGuard.root();
        Path candidate = pathGuard.resolve(Path.of(relativeDirectory));
        Path directory;
        try {
            directory = pathGuard.requireDirectory(candidate);
        } catch (IOException missingOrUnsafe) {
            return List.of();
        }

        List<String> found = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                    throws IOException {
                budget.visit(current);
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(current)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.visit(file);
                if (attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)
                        || !attributes.isRegularFile()
                        || !file.getFileName().toString().toLowerCase().endsWith(suffix.toLowerCase())) {
                    return FileVisitResult.CONTINUE;
                }
                Path safeFile;
                try {
                    safeFile = pathGuard.requireRegularFile(file);
                } catch (IOException unsafeEntry) {
                    return FileVisitResult.CONTINUE;
                }
                budget.candidate(safeFile);
                found.add(repositoryPath(root.relativize(safeFile)));
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(String::compareTo);
        return List.copyOf(found);
    }

    private static void putIfNotEmpty(
            Map<String, List<String>> target,
            String key,
            List<String> values) {
        if (!values.isEmpty()) {
            target.put(key, List.copyOf(values));
        }
    }

    private static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
