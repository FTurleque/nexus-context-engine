package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.LexicalSearchHit;
import com.nexus.index.SearchDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneSearchIndexTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void excludesSingleUniqueTermEvenWhenItMatchesSeveralFields() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(
                document("orchard-only.md", "orchard orchard orchard unrelated"),
                document("compass-guide.md", "orchard compass")));
        List<LexicalSearchHit> hits = index.search(projectId, "orchard-compass", 10);

        assertFalse(hits.isEmpty());
        assertEquals("compass-guide.md", hits.getFirst().relativePath());
        assertTrue(hits.stream().noneMatch(hit -> "orchard-only.md".equals(hit.relativePath())));
    }

    @Test
    void keepsPartialRecallForQueriesLongerThanTwoUniqueTerms() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(
                document("two-terms.md", "layered boundary"),
                document("one-term.md", "layered layered layered")));
        List<LexicalSearchHit> hits = index.search(projectId, "layered boundary ports", 10);

        assertFalse(hits.isEmpty());
        assertEquals("two-terms.md", hits.getFirst().relativePath());
        assertTrue(hits.stream().noneMatch(hit -> "one-term.md".equals(hit.relativePath())));
    }

    @Test
    void leavesSingleTermQueriesUnchanged() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(document("singleton.md", "singleton")));
        List<LexicalSearchHit> hits = index.search(projectId, "singleton", 10);

        assertEquals(1, hits.size());
        assertEquals("singleton.md", hits.getFirst().relativePath());
    }

    @Test
    void redactsSensitiveContentBeforeBuildingInvertedTerms() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("redacted-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(document(
                "secret.md",
                "password=\"uniquesecretvalue2026\"; searchableMarker remains visible")));

        assertTrue(index.search(projectId, "uniquesecretvalue2026", 10).isEmpty());
        assertEquals(1, index.search(projectId, "searchableMarker", 10).size());
    }

    @Test
    void boundsHighCardinalityQueriesInsteadOfTriggeringTooManyClauses() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("bounded-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(document("bounded.md", "q0 q1 q2")));
        String query = IntStream.range(0, 1_500)
                .mapToObj(indexValue -> "q" + indexValue)
                .collect(Collectors.joining(" "));

        List<LexicalSearchHit> hits = assertDoesNotThrow(() -> index.search(projectId, query, 10));

        assertFalse(hits.isEmpty());
        assertEquals("bounded.md", hits.getFirst().relativePath());
        assertEquals(128, LuceneSearchIndex.MAX_ANALYZED_QUERY_TERMS);
    }

    @Test
    void boundsRepeatedSingleTermQueriesWithoutPassingTheRawQueryToLucene() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("repeated-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(document("repeated.md", "alpha")));

        String query = "alpha ".repeat(1_100);

        List<LexicalSearchHit> hits = assertDoesNotThrow(() -> index.search(projectId, query, 10));

        assertEquals(1, hits.size());
        assertEquals("repeated.md", hits.getFirst().relativePath());
    }

    @Test
    void closesAllHandlesAfterRebuildAndSearch() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("lifecycle-home"));
        LuceneSearchIndex index = new LuceneSearchIndex(paths);
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(document("lifecycle.md", "lifecycle resource handles")));
        assertEquals(1, index.search(projectId, "lifecycle", 10).size());

        deleteTree(paths.projectLuceneIndex(projectId));
        assertFalse(Files.exists(paths.projectLuceneIndex(projectId)));
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void excludesOnlyTheDocumentWhoseSecretExceedsTheRedactionBound() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("oversized-home")));
        UUID projectId = UUID.randomUUID();
        String oversized = "password = \"" + "x".repeat(5_000) + "\" oversizedMarker";

        index.rebuild(projectId, List.of(document("big.md", oversized), document("small.md", "visibleMarker")));
        assertEquals(1, index.search(projectId, "visibleMarker", 10).size());
        assertTrue(index.search(projectId, "oversizedMarker", 10).isEmpty());

        index.applyChanges(projectId, List.of(document("small.md", oversized)), java.util.Set.of());
        assertTrue(index.search(projectId, "visibleMarker", 10).isEmpty());
        assertTrue(index.search(projectId, "oversizedMarker", 10).isEmpty());
    }

    @Test
    void persistentPresenceProbeOpensNoReaderOutsideTheCache() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("presence-home"));
        UUID projectId = UUID.randomUUID();
        try (PersistentLuceneSearchIndex index = new PersistentLuceneSearchIndex(paths)) {
            // Répertoire absent : « absent » sans erreur.
            assertFalse(index.isPresent(projectId));
            assertFalse(index.isPresent(UUID.randomUUID()));

            index.rebuild(projectId, List.of(document("guide.md", "orchard compass")));

            // Sonde répétée sans reader en cache : dernier commit lu, aucun DirectoryReader ouvert.
            assertTrue(index.isPresent(projectId));
            assertTrue(index.isPresent(projectId));
            assertEquals(0, index.openedReaderCount());

            // Une recherche ouvre le reader en cache une fois ; les sondes suivantes le réutilisent.
            assertEquals(1, index.search(projectId, "orchard", 5).size());
            assertEquals(1, index.openedReaderCount());
            assertTrue(index.isPresent(projectId));
            assertTrue(index.isPresent(projectId));
            assertEquals(1, index.openedReaderCount());
        }
    }

    @Test
    void redactionPolicyCheckRejectsMissingOrObsoleteCommitPolicy() {
        assertFalse(LuceneSearchIndex.hasCurrentRedactionPolicy(java.util.Map.of()));
        assertFalse(LuceneSearchIndex.hasCurrentRedactionPolicy(
                java.util.Map.of("nexus.redaction.policy", "obsolete-policy")));
    }

    private static SearchDocument document(String relativePath, String content) {
        return new SearchDocument(relativePath, "markdown", FileCategory.DOCUMENTATION, content, List.of());
    }
}
