package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.index.SearchDocument;
import com.nexus.search.SearchIndexSession;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Un writer par indexation, un seul commit à la barrière de publication (design D4, DATA-0045). */
class LuceneSearchIndexSessionTest {

    private static final int BATCH_FILES = 128;

    @TempDir
    Path temporaryDirectory;

    @Test
    void commitsOnceForMoreThanOneBatchOfFilesAndNothingIsVisibleBeforeTheCommit() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSearchIndex index = new LuceneSearchIndex(paths);
        UUID projectId = UUID.randomUUID();
        int batches = 3;

        try (SearchIndexSession session = index.openSession(projectId, true)) {
            for (int batch = 0; batch < batches; batch++) {
                session.applyChanges(documents("batch" + batch, BATCH_FILES), Set.of());
            }
            assertTrue(index.search(projectId, "marker", 10).isEmpty(),
                    "aucun document n'est visible avant le commit");
            session.commit();
        }

        int documents = batches * BATCH_FILES;
        assertTrue(documents > BATCH_FILES);
        assertEquals(1L, commitGeneration(paths, projectId), "un seul commit pour toute l'indexation");
        assertEquals(documents, documentCount(paths, projectId));
    }

    @Test
    void anIncrementalSessionWithoutChangeOpensNothingAndCommitsNothing() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSearchIndex index = new LuceneSearchIndex(paths);
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, documents("seed", 3));
        long before = commitGeneration(paths, projectId);

        try (SearchIndexSession session = index.openSession(projectId, false)) {
            session.applyChanges(List.of(), Set.of());
            session.commit();
        }

        assertEquals(before, commitGeneration(paths, projectId));
        assertEquals(3, documentCount(paths, projectId));
    }

    @Test
    void closingWithoutCommitRollsBackAndKeepsThePreviousIndex() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSearchIndex index = new LuceneSearchIndex(paths);
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, documents("previous", 2));
        long before = commitGeneration(paths, projectId);

        try (SearchIndexSession session = index.openSession(projectId, true)) {
            session.applyChanges(documents("replacement", BATCH_FILES + 1), Set.of());
            session.applyChanges(documents("replacement-bis", 5), Set.of("previous-0.md"));
            // Pas de commit : l'indexation a échoué avant la publication.
        }

        assertEquals(before, commitGeneration(paths, projectId));
        assertEquals(2, documentCount(paths, projectId));
        assertEquals(2, index.search(projectId, "previous", 10).size());
        assertTrue(index.search(projectId, "replacement", 10).isEmpty());
        // Le verrou du writer est libéré : une indexation suivante peut écrire.
        try (SearchIndexSession next = index.openSession(projectId, false)) {
            next.applyChanges(documents("next", 1), Set.of());
            next.commit();
        }
        assertEquals(3, documentCount(paths, projectId));
    }

    @Test
    void anIncrementalSessionAppliesRemovalsAndUpdatesAtTheSingleCommit() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        LuceneSearchIndex index = new LuceneSearchIndex(paths);
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, documents("seed", 4));
        long before = commitGeneration(paths, projectId);

        try (SearchIndexSession session = index.openSession(projectId, false)) {
            session.applyChanges(List.of(), Set.of("seed-0.md"));
            session.applyChanges(List.of(document("seed-1.md", "updated")), Set.of());
            session.commit();
        }

        assertEquals(before + 1, commitGeneration(paths, projectId));
        assertEquals(3, documentCount(paths, projectId));
        assertEquals(List.of("seed-1.md"),
                index.search(projectId, "updated", 10).stream().map(hit -> hit.relativePath()).toList());
    }

    private static List<SearchDocument> documents(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> document(prefix + "-" + index + ".md", prefix + " marker"))
                .toList();
    }

    private static SearchDocument document(String path, String content) {
        return new SearchDocument(path, "MARKDOWN", FileCategory.DOCUMENTATION, content, List.of());
    }

    private long commitGeneration(NexusPaths paths, UUID projectId) throws IOException {
        try (Directory directory = FSDirectory.open(paths.projectLuceneIndex(projectId));
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.getIndexCommit().getGeneration();
        }
    }

    private int documentCount(NexusPaths paths, UUID projectId) throws IOException {
        try (Directory directory = FSDirectory.open(paths.projectLuceneIndex(projectId));
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }
}
