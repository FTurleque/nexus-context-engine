package com.nexus.project;

import java.util.Objects;

/** Invariant commun aux lectures d'un index publié. */
public final class ProjectReadiness {
    private ProjectReadiness() { }

    public static ProjectDescriptor requireReady(ProjectDescriptor project) {
        Objects.requireNonNull(project, "project");
        if (project.indexStatus() != IndexStatus.READY) {
            throw new IllegalStateException("Le projet " + project.id()
                    + " n'est pas READY (état " + project.indexStatus() + ")");
        }
        return project;
    }
}
