package com.nexus.search.semantic;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Session de repli pour les index vectoriels sans writer propre : chaque lot est appliqué par
 * {@link SemanticSearchIndex#applyChanges} et {@link #commit()} ne fait rien.
 */
final class DirectSemanticIndexSession implements SemanticIndexSession {
    private final SemanticSearchIndex index;
    private final UUID projectId;
    private final SemanticIndexProvenance provenance;

    DirectSemanticIndexSession(
            SemanticSearchIndex index,
            UUID projectId,
            SemanticIndexProvenance provenance,
            boolean rebuild) throws IOException {
        this.index = Objects.requireNonNull(index, "index");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        if (rebuild) {
            index.rebuild(projectId, provenance, List.of());
        }
    }

    @Override
    public void applyChanges(List<SemanticVectorDocument> documents, Set<String> removedRelativePaths)
            throws IOException {
        index.applyChanges(projectId, provenance, documents, removedRelativePaths);
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
