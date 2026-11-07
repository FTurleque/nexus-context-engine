package com.nexus.search;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SearchIndex extends AutoCloseable {

    void applyChanges(UUID projectId, List<SearchDocument> documents, Set<String> removedPaths) throws IOException;

    void rebuild(UUID projectId, List<SearchDocument> documents) throws IOException;

    List<LexicalSearchHit> search(UUID projectId, String query, int limit) throws IOException;

    /**
     * Ouvre les écritures d'une indexation. Avec {@code rebuild}, le contenu du projet est vidé dans
     * la session. Par défaut, chaque lot est appliqué immédiatement par {@link #applyChanges} ;
     * les index qui savent différer leur commit (Lucene) le font à {@link SearchIndexSession#commit()}.
     */
    default SearchIndexSession openSession(UUID projectId, boolean rebuild) throws IOException {
        return new DirectSearchIndexSession(this, projectId, rebuild);
    }

    /** Indique si l'index dérivé existe et peut être ouvert pour le projet. */
    default boolean isPresent(UUID projectId) throws IOException {
        return true;
    }

    /**
     * Libère les ressources persistantes éventuelles. Les implémentations
     * operation-scoped historiques n'ont rien à fermer et restent compatibles.
     */
    @Override
    default void close() throws IOException {
        // no-op par défaut
    }
}
