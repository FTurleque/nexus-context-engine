package com.nexus.context.source.skill;

import com.nexus.project.ProjectDescriptor;

import java.util.Objects;

/**
 * Paramètres fournis aux providers de catalogues de skills.
 */
public record SkillSourceQuery(
        ProjectDescriptor project,
        boolean explain) {

    public SkillSourceQuery {
        Objects.requireNonNull(project, "project");
    }
}
