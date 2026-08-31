package com.nexus.context.source.skill;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.project.ProjectDescriptor;

import java.util.Objects;

/**
 * Paramètres fournis aux providers de catalogues de skills.
 */
public record SkillSourceQuery(
        ProjectDescriptor project,
        boolean explain,
        ContextDiscoveryBudget discoveryBudget) {

    public SkillSourceQuery {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(discoveryBudget, "discoveryBudget");
    }

    public SkillSourceQuery(ProjectDescriptor project, boolean explain) {
        this(project, explain, ContextDiscoveryLimits.defaults().newBudget());
    }
}
