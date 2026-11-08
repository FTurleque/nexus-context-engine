package com.nexus.search.semantic;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Écritures d'un index vectoriel pour la durée d'une indexation (DATA-0074 : l'opération est
 * l'indexation). Les écritures et la provenance ne deviennent visibles qu'au {@link #commit()} ;
 * {@link #close()} sans commit les annule et l'index garde le contenu et la provenance de la
 * génération précédente.
 */
public interface SemanticIndexSession extends AutoCloseable {

    /** Applique un lot de vecteurs et de suppressions, sans le rendre visible. */
    void applyChanges(List<SemanticVectorDocument> documents, Set<String> removedRelativePaths)
            throws IOException;

    /** Rend visibles les écritures et la provenance de la session, en un seul commit. À appeler au plus une fois. */
    void commit() throws IOException;

    /** Annule les écritures non committées et libère les ressources de la session. */
    @Override
    void close() throws IOException;
}
