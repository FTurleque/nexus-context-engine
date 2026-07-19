package com.nexus.context.source;

import com.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path root = project.rootPath().toAbsolutePath().normalize();
        Map<String, List<String>> detected = new LinkedHashMap<>();

        List<String> operational = existing(root, List.of(
                ".claude/settings.json",
                ".claude/settings.local.json",
                ".mcp.json",
                "mcp.json",
                ".gemini/settings.json"));
        putIfNotEmpty(detected, "operationalConfigurations", operational);

        List<String> agentProfiles = new ArrayList<>();
        agentProfiles.addAll(findBelow(root, ".github/agents", ".md"));
        agentProfiles.addAll(findBelow(root, ".claude/agents", ".md"));
        putIfNotEmpty(detected, "agentProfiles", agentProfiles);

        List<String> hooks = new ArrayList<>();
        hooks.addAll(findBelow(root, ".github/hooks", ".json"));
        hooks.addAll(findBelow(root, ".claude/hooks", ".json"));
        putIfNotEmpty(detected, "hooks", hooks);

        return Map.copyOf(detected);
    }

    private static List<String> existing(Path root, List<String> relativePaths) {
        return relativePaths.stream()
                .filter(relative -> Files.isRegularFile(root.resolve(relative)))
                .toList();
    }

    private static List<String> findBelow(Path root, String relativeDirectory, String suffix) throws IOException {
        Path directory = root.resolve(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase()
                            .endsWith(suffix.toLowerCase()))
                    .map(root::relativize)
                    .map(NativeProjectCustomizationDetector::repositoryPath)
                    .sorted()
                    .toList();
        }
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
