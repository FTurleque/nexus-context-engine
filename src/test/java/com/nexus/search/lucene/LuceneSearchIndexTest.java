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

    private static SearchDocument document(String relativePath, String content) {
        return new SearchDocument(relativePath, "markdown", FileCategory.DOCUMENTATION, content, List.of());
    }
}
