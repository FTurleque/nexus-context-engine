package com.nexus.index;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Indexation sémantique désactivée : aucune écriture, aucun index dérivé. */
final class NoopSemanticIndexing implements SemanticIndexing {

    static final NoopSemanticIndexing INSTANCE = new NoopSemanticIndexing();

    private static final Session NOOP_SESSION = new Session() {
        @Override
        public void applyChanges(List<SearchDocument> documents, Set<String> removedRelativePaths) {
            // Indexation sémantique désactivée : les changements ne produisent aucun index dérivé.
        }

        @Override
        public void commit() {
            // Rien à valider : aucune écriture n'a été préparée.
        }

        @Override
        public void close() {
            // Aucune ressource ouverte.
        }
    };

    private NoopSemanticIndexing() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean isCompatible(UUID projectId, String canonicalFingerprint) {
        return true;
    }

    @Override
    public Session openSession(UUID projectId, String canonicalFingerprint, boolean rebuild) {
        return NOOP_SESSION;
    }
}
