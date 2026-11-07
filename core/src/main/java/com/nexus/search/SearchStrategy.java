package com.nexus.search;

import com.nexus.project.ProjectDescriptor;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public interface SearchStrategy {

    List<SearchCandidate> search(ProjectDescriptor project, String query, int limit) throws IOException;

    /**
     * Recherche avec le contexte de la requête, partagé entre les projets d'une
     * requête fédérée. Le comportement par défaut ignore le contexte : seules les
     * stratégies qui mutualisent un coût dépendant de la requête seule (vecteur
     * d'embedding) le surchargent.
     */
    default List<SearchCandidate> search(
            ProjectDescriptor project,
            String query,
            int limit,
            SearchQueryContext queryContext) throws IOException {
        Objects.requireNonNull(queryContext, "queryContext");
        return search(project, query, limit);
    }
}
