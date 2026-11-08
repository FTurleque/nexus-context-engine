package com.nexus.search;

import com.nexus.index.SearchDocument;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Écritures d'un index lexical pour la durée d'une indexation (DATA-0074 : l'opération est
 * l'indexation). Les écritures ne deviennent visibles qu'au {@link #commit()}, la barrière de
 * publication ; {@link #close()} sans commit les annule et l'index reste celui de la génération
 * précédente.
 */
public interface SearchIndexSession extends AutoCloseable {

    /** Applique un lot de documents et de suppressions, sans le rendre visible. */
    void applyChanges(List<SearchDocument> documents, Set<String> removedPaths) throws IOException;

    /** Rend visibles toutes les écritures de la session, en un seul commit. À appeler au plus une fois. */
    void commit() throws IOException;

    /** Annule les écritures non committées et libère les ressources de la session. */
    @Override
    void close() throws IOException;
}
