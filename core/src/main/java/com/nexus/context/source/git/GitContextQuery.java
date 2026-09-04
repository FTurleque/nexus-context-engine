package com.nexus.context.source.git;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.project.ProjectDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record GitContextQuery(
        ProjectDescriptor project,
        String query,
        List<Path> targetPaths,
        boolean explain,
        ContextDiscoveryBudget discoveryBudget) {

    public GitContextQuery {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(targetPaths, "targetPaths");
        Objects.requireNonNull(discoveryBudget, "discoveryBudget");
        targetPaths = List.copyOf(targetPaths);
    }

    public GitContextQuery(
            ProjectDescriptor project,
            String query,
            List<Path> targetPaths,
            boolean explain) {
        this(project, query, targetPaths, explain, ContextDiscoveryLimits.defaults().newBudget());
    }
}
