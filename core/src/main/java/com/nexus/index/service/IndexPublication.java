package com.nexus.index.service;

import com.nexus.index.IndexWriteSession;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.SearchDocument;
import com.nexus.index.SemanticIndexing;
import com.nexus.search.SearchIndexSession;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Écritures d'une indexation : une session lexicale, une session sémantique optionnelle (un seul
 * writer Lucene chacune, DATA-0074) et une session canonique SQLite (mise en attente par lots).
 * Rien n'est visible avant {@link #commitDerivedIndexes()} et
 * {@link #publishCanonical(Set)} ; {@link #close()} sans publication annule les écritures Lucene
 * non committées et écarte les analyses en attente.
 *
 * <p>Ordre de la barrière de publication, fixé par {@code ProjectIndexingService} : commit des
 * index dérivés, puis publication canonique (qui incrémente la génération), puis scan de contrôle,
 * et enfin le statut {@code READY}. Rien n'est servi avant {@code READY}.</p>
 */
final class IndexPublication implements AutoCloseable {
    private final SearchIndexSession lexical;
    private final SemanticIndexing.Session semantic;
    private final IndexWriteSession writes;

    IndexPublication(
            SearchIndexSession lexical,
            SemanticIndexing.Session semantic,
            IndexWriteSession writes) {
        this.lexical = Objects.requireNonNull(lexical, "lexical");
        this.semantic = semantic;
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    boolean hasSemantic() {
        return semantic != null;
    }

    void applyLexical(List<SearchDocument> documents, Set<String> removedPaths) throws IOException {
        lexical.applyChanges(documents, removedPaths);
    }

    void applySemantic(List<SearchDocument> documents, Set<String> removedPaths) throws IOException {
        if (semantic != null) {
            semantic.applyChanges(documents, removedPaths);
        }
    }

    void stage(List<IndexedFileUpdate> updates) {
        writes.stage(updates);
    }

    /** Commit unique de chaque index dérivé : la barrière de publication Lucene. */
    void commitDerivedIndexes() throws IOException {
        lexical.commit();
        if (semantic != null) {
            semantic.commit();
        }
    }

    /** Applique les analyses en attente et les suppressions, et publie la génération canonique. */
    void publishCanonical(Set<String> removedPaths) {
        writes.publish(removedPaths);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            lexical.close();
        } catch (IOException | RuntimeException closeFailure) {
            failure = merge(failure, closeFailure);
        }
        if (semantic != null) {
            try {
                semantic.close();
            } catch (IOException | RuntimeException closeFailure) {
                failure = merge(failure, closeFailure);
            }
        }
        writes.close();
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException merge(IOException primary, Exception secondary) {
        IOException wrapped = secondary instanceof IOException io ? io : new IOException(secondary);
        if (primary == null) {
            return wrapped;
        }
        primary.addSuppressed(wrapped);
        return primary;
    }
}
