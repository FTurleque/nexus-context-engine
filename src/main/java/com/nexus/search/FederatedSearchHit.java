package com.nexus.search;

import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;

import java.util.Objects;

/**
 * Résultat de recherche fédérée conservant explicitement la provenance du projet.
 */
public record FederatedSearchHit(
        ProjectDescriptor project,
        RankedCandidate rankedCandidate) {

    public FederatedSearchHit {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(rankedCandidate, "rankedCandidate");
    }
}
