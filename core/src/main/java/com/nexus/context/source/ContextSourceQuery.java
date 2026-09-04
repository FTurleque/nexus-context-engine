package com.nexus.context.source;

import com.nexus.project.ProjectDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Contexte minimal fourni aux providers pour résoudre le scope d'une source.
 * Les chemins cibles sont relatifs à la racine du projet.
 */
public record ContextSourceQuery(
        ProjectDescriptor project,
        String query,
        List<Path> targetPaths,
        boolean explain,
        ContextDiscoveryBudget discoveryBudget) {

    public ContextSourceQuery {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(targetPaths, "targetPaths");
        Objects.requireNonNull(discoveryBudget, "discoveryBudget");
        targetPaths = List.copyOf(targetPaths);
    }

    public ContextSourceQuery(
            ProjectDescriptor project,
            String query,
            List<Path> targetPaths,
            boolean explain) {
        this(project, query, targetPaths, explain, ContextDiscoveryLimits.defaults().newBudget());
    }
}
