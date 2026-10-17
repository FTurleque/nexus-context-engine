package com.nexus.mcp;

import com.nexus.application.NexusApplication;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.ProjectDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nexus.mcp.McpToolArguments.requiredString;

/** Responsabilité interne de la frontière MCP. */
final class McpProjectResolver {
    static final int MAX_PROJECT_SELECTORS = FederatedScopePolicy.MAX_PROJECTS * 2;
    private final NexusApplication application;
    McpProjectResolver(NexusApplication application) { this.application = application; }
    ProjectDescriptor resolveProject(Map<String, Object> arguments) {
        return application.resolveProject(requiredString(arguments, "project"));
    }

    List<ProjectDescriptor> resolveProjects(Map<String, Object> arguments) {
        Object value = arguments.get("projects");
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("projects doit être un tableau non vide");
        }
        if (values.size() > MAX_PROJECT_SELECTORS) {
            throw new IllegalArgumentException(
                    "projects doit contenir au plus " + MAX_PROJECT_SELECTORS + " sélecteurs");
        }

        Map<String, String> uniqueSelectors = new LinkedHashMap<>();
        for (Object rawSelector : values) {
            if (!(rawSelector instanceof String rawString) || rawString.isBlank()) {
                throw new IllegalArgumentException("projects doit contenir uniquement des chaînes non vides");
            }
            String selector = rawString.trim();
            uniqueSelectors.putIfAbsent(selector.toLowerCase(Locale.ROOT), selector);
        }
        List<String> selectors = List.copyOf(uniqueSelectors.values());

        FederatedScopePolicy.validateExplicitUuidSelectors(selectors);

        List<ProjectDescriptor> projects = new ArrayList<>();
        Set<UUID> seen = new java.util.LinkedHashSet<>();
        for (String selector : selectors) {
            ProjectDescriptor project = application.resolveProject(selector);
            if (seen.add(project.id())) {
                FederatedScopePolicy.validateUniqueCount(seen.size());
                projects.add(project);
            }
        }
        return List.copyOf(projects);
    }

}
