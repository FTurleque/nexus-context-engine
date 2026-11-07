package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.semantic.SemanticIndexProvenance;
import com.nexus.search.semantic.SemanticIndexSession;
import com.nexus.search.semantic.SemanticVectorDocument;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Un writer par indexation, un seul commit (et une seule provenance publiée) à la barrière de publication. */
class LuceneSemanticSearchIndexSessionTest {

    private static final int BATCH_FILES = 128;

    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsOnceForMoreThanOneBatchOfFilesAndPublishesTheProvenanceAtTheCommit() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, 3);
        UUID projectId = UUID.randomUUID();
        SemanticIndexProvenance provenance = provenance("fingerprint-1");
        int batches = 3;

        try (SemanticIndexSession session = index.openSession(projectId, provenance, true)) {
            for (int batch = 0; batch < batches; batch++) {
                session.applyChanges(documents("batch" + batch, BATCH_FILES), Set.of());
            }
            assertFalse(index.isCompatible(projectId, provenance), "la provenance n'est pas publiée avant le commit");
            session.commit();
        }

        assertTrue(batches * BATCH_FILES > BATCH_FILES);
        assertEquals(1L, commitGeneration(paths, projectId), "un seul commit pour toute l'indexation");
        assertEquals(batches * BATCH_FILES, documentCount(paths, projectId));
        assertTrue(index.isCompatible(projectId, provenance));
    }

    @Test
    void closingWithoutCommitKeepsThePreviousContentAndProvenance() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, 3);
        UUID projectId = UUID.randomUUID();
        SemanticIndexProvenance previous = provenance("fingerprint-1");
        SemanticIndexProvenance next = provenance("fingerprint-2");
        index.rebuild(projectId, previous, documents("previous", 2));
        long before = commitGeneration(paths, projectId);

        try (SemanticIndexSession session = index.openSession(projectId, next, true)) {
            session.applyChanges(documents("replacement", BATCH_FILES + 1), Set.of());
            // Pas de commit : l'indexation a échoué avant la publication.
        }

        assertEquals(before, commitGeneration(paths, projectId));
        assertEquals(2, documentCount(paths, projectId));
        assertTrue(index.isCompatible(projectId, previous));
        assertFalse(index.isCompatible(projectId, next));
        // Le verrou du writer est libéré : une indexation suivante peut écrire.
        try (SemanticIndexSession retry = index.openSession(projectId, next, false)) {
            retry.applyChanges(documents("retry", 1), Set.of());
            retry.commit();
        }
        assertEquals(3, documentCount(paths, projectId));
        assertTrue(index.isCompatible(projectId, next));
    }

    @Test
    void anIncrementalSessionWithoutChangeCommitsNothing() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, 3);
        UUID projectId = UUID.randomUUID();
        SemanticIndexProvenance previous = provenance("fingerprint-1");
        index.rebuild(projectId, previous, documents("seed", 2));
        long before = commitGeneration(paths, projectId);

        try (SemanticIndexSession session = index.openSession(projectId, provenance("fingerprint-2"), false)) {
            session.applyChanges(List.of(), Set.of());
            session.commit();
        }

        assertEquals(before, commitGeneration(paths, projectId));
        assertTrue(index.isCompatible(projectId, previous));
    }

    private static SemanticIndexProvenance provenance(String fingerprint) {
        return new SemanticIndexProvenance(fingerprint, "provider", "model", 3, "profile", 1);
    }

    private static List<SemanticVectorDocument> documents(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new SemanticVectorDocument(
                        prefix + "-" + index + ".md", FileCategory.DOCUMENTATION, prefix, new float[] {1, 0, 0}))
                .toList();
    }

    private long commitGeneration(NexusPaths paths, UUID projectId) throws IOException {
        try (Directory directory = FSDirectory.open(paths.projectSemanticLuceneIndex(projectId));
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.getIndexCommit().getGeneration();
        }
    }

    private int documentCount(NexusPaths paths, UUID projectId) throws IOException {
        try (Directory directory = FSDirectory.open(paths.projectSemanticLuceneIndex(projectId));
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }
}
