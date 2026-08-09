package com.nexus.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Common cardinality contract for every federated NEXUS surface. */
public final class FederatedScopePolicy {

    public static final int MAX_PROJECTS = 100;
    public static final String TOO_MANY_PROJECTS_MESSAGE =
            "federated scope must not exceed " + MAX_PROJECTS + " projects";

    private FederatedScopePolicy() {
    }

    public static List<UUID> normalizeProjectIds(List<UUID> projectIds) {
        Objects.requireNonNull(projectIds, "projectIds");
        if (projectIds.isEmpty()) {
            throw new IllegalArgumentException("projectIds must not be empty");
        }
        Map<UUID, UUID> unique = new LinkedHashMap<>();
        for (UUID projectId : projectIds) {
            UUID nonNull = Objects.requireNonNull(projectId, "projectId");
            unique.putIfAbsent(nonNull, nonNull);
            validateUniqueCount(unique.size());
        }
        return List.copyOf(unique.keySet());
    }

    public static List<ProjectDescriptor> normalizeProjects(List<ProjectDescriptor> projects) {
        Objects.requireNonNull(projects, "projects");
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("projects must not be empty");
        }
        Map<UUID, ProjectDescriptor> unique = new LinkedHashMap<>();
        for (ProjectDescriptor project : projects) {
            ProjectDescriptor nonNull = Objects.requireNonNull(project, "project");
            unique.putIfAbsent(nonNull.id(), nonNull);
            validateUniqueCount(unique.size());
        }
        return List.copyOf(unique.values());
    }

    public static void validateUniqueCount(int uniqueProjectCount) {
        if (uniqueProjectCount > MAX_PROJECTS) {
            throw new IllegalArgumentException(TOO_MANY_PROJECTS_MESSAGE);
        }
    }
}
