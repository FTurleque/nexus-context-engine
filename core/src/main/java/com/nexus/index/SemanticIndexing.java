package com.nexus.index;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port d'indexation sémantique consommé par l'orchestration de l'indexation.
 *
 * <p>Une recherche sémantique désactivée est représentée par {@link #disabled()}, implémentation
 * sans effet dont {@link #enabled()} vaut {@code false} : l'orchestration ne prépare alors aucun
 * document sémantique et n'ouvre aucune session.</p>
 */
public interface SemanticIndexing {

    /** Implémentation sans effet : aucune indexation sémantique n'est composée. */
    static SemanticIndexing disabled() {
        return NoopSemanticIndexing.INSTANCE;
    }

    /** {@code true} lorsqu'un provider d'embeddings et un index sémantique sont composés. */
    boolean enabled();

    /** Indique si l'index sémantique du projet correspond à l'empreinte canonique et au profil courants. */
    boolean isCompatible(UUID projectId, String canonicalFingerprint) throws IOException;

    /**
     * Ouvre les écritures d'une indexation : rien n'est visible avant {@link Session#commit()}.
     */
    Session openSession(UUID projectId, String canonicalFingerprint, boolean rebuild) throws IOException;

    /** Écritures sémantiques d'une indexation. */
    interface Session extends AutoCloseable {

        void applyChanges(List<SearchDocument> documents, Set<String> removedRelativePaths) throws IOException;

        void commit() throws IOException;

        @Override
        void close() throws IOException;
    }
}
