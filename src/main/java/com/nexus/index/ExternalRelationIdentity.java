package com.nexus.index;

import java.util.Objects;

/**
 * Canonical identity of one externally supplied relation fact.
 *
 * <p>The originating file is part of the identity because the same logical source/target relation
 * may be independently evidenced by several files. Confidence is deliberately excluded: duplicate
 * occurrences of the same fact are collapsed deterministically by retaining the maximum confidence.</p>
 */
public record ExternalRelationIdentity(
        String relativePath,
        RelationKind kind,
        String source,
        String target,
        String sourceProvider) {

    public ExternalRelationIdentity {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
    }

    public static ExternalRelationIdentity of(IndexedRelation indexedRelation) {
        Objects.requireNonNull(indexedRelation, "indexedRelation");
        SymbolRelation relation = indexedRelation.relation();
        return new ExternalRelationIdentity(
                indexedRelation.relativePath(),
                relation.kind(),
                relation.source(),
                relation.target(),
                relation.sourceProvider());
    }
}
