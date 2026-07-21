package com.nexus.search.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.LexicalSearchHit;
import com.nexus.search.SearchDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneSearchIndexTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void excludesSingleTermFalsePositivesForTwoTermQuery() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(
                document("false-positive.md", "candidate candidate candidate unrelated"),
                document("relevant.md", "ariane.search.candidate-multiplier=5")));

        List<LexicalSearchHit> hits = index.search(projectId, "candidate-multiplier", 10);

        assertFalse(hits.isEmpty());
        assertEquals("relevant.md", hits.getFirst().relativePath());
        assertTrue(hits.stream().noneMatch(hit -> "false-positive.md".equals(hit.relativePath())));
    }

    @Test
    void keepsPartialRecallForQueriesLongerThanTwoTerms() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(
                document("two-terms.md", "architecture hexagonale"),
                document("one-term.md", "architecture architecture architecture")));

        List<LexicalSearchHit> hits = index.search(projectId, "architecture hexagonale ports", 10);

        assertFalse(hits.isEmpty());
        assertEquals("two-terms.md", hits.getFirst().relativePath());
        assertTrue(hits.stream().noneMatch(hit -> "one-term.md".equals(hit.relativePath())));
    }

    @Test
    void leavesSingleTermQueriesUnchanged() throws Exception {
        LuceneSearchIndex index = new LuceneSearchIndex(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(document("candidate.md", "candidate")));

        List<LexicalSearchHit> hits = index.search(projectId, "candidate", 10);

        assertEquals(1, hits.size());
        assertEquals("candidate.md", hits.getFirst().relativePath());
    }

    private static SearchDocument document(String relativePath, String content) {
        return new SearchDocument(relativePath, "markdown", FileCategory.DOCUMENTATION, content, List.of());
    }
}
