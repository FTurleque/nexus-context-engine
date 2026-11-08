package com.nexus.search;

import com.nexus.project.ProjectDescriptor;

import java.util.List;
import java.util.Objects;

/**
 * Résultat d'une recherche fédérée : les résultats des projets servis et, dans
 * l'ordre du scope, les projets que l'échéance globale a empêché de commencer
 * (BE-0064). La liste des projets non servis est vide sans coupe.
 */
public record FederatedSearchOutcome(
        List<FederatedSearchHit> hits,
        List<ProjectDescriptor> unservedProjects) {

    public FederatedSearchOutcome {
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        unservedProjects = List.copyOf(Objects.requireNonNull(unservedProjects, "unservedProjects"));
    }
}
