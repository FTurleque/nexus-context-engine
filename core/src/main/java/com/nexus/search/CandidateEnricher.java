package com.nexus.search;

import com.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Enrichit une liste de candidats avec des signaux ou des candidats dérivés
 * avant le classement final.
 */
public interface CandidateEnricher {

    List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> candidates) throws IOException;

    /**
     * Enrichit les candidats et signale à {@code explanationReasons} les raisons d'explication
     * qui portent sur l'ensemble du résultat (signal indisponible, par exemple).
     */
    default List<SearchCandidate> enrich(
            ProjectDescriptor project,
            List<SearchCandidate> candidates,
            Consumer<String> explanationReasons) throws IOException {
        return enrich(project, candidates);
    }
}
