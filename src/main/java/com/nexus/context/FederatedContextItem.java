package com.nexus.context;

import com.nexus.project.ProjectDescriptor;

import java.util.Objects;

/**
 * Élément de contexte fédéré avec provenance explicite du projet d'origine.
 */
public record FederatedContextItem(
        ProjectDescriptor project,
        ContextItem item) {

    public FederatedContextItem {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(item, "item");
    }
}
