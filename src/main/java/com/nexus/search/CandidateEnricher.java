package com.nexus.search;

import com.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.util.List;

/**
 * Enrichit une liste de candidats avec des signaux ou des candidats dérivés
 * avant le classement final.
 */
public interface CandidateEnricher {

    List<SearchCandidate> enrich(ProjectDescriptor project, List<SearchCandidate> candidates) throws IOException;
}
