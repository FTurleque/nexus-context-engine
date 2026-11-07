package com.nexus.search;

import com.nexus.index.SearchDocument;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Session de repli pour les index qui n'ont pas de writer propre : chaque lot est appliqué par
 * {@link SearchIndex#applyChanges} (donc visible immédiatement) et {@link #commit()} ne fait rien.
 * Les index Lucene de production ont leur propre session, committée une seule fois.
 */
final class DirectSearchIndexSession implements SearchIndexSession {
    private final SearchIndex index;
    private final UUID projectId;

    DirectSearchIndexSession(SearchIndex index, UUID projectId, boolean rebuild) throws IOException {
        this.index = Objects.requireNonNull(index, "index");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        if (rebuild) {
            index.rebuild(projectId, List.of());
        }
    }

    @Override
    public void applyChanges(List<SearchDocument> documents, Set<String> removedPaths) throws IOException {
        index.applyChanges(projectId, documents, removedPaths);
    }

    @Override
    public void commit() {
        // Chaque lot a déjà été appliqué.
    }

    @Override
    public void close() {
        // Rien à libérer.
    }
}
