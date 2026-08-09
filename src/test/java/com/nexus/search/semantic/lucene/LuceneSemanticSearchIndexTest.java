package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.semantic.SemanticSearchHit;
import com.nexus.search.semantic.SemanticVectorDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LuceneSemanticSearchIndexTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rebuildsAndFindsNearestDocuments() throws Exception {
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")),
                3);
        UUID projectId = UUID.randomUUID();

        index.rebuild(projectId, List.of(
                document("docs/architecture.md", FileCategory.DOCUMENTATION, "architecture", 1.0f, 0.0f, 0.0f),
                document("docs/database.md", FileCategory.DOCUMENTATION, "database", 0.0f, 1.0f, 0.0f)));

        List<SemanticSearchHit> hits = index.search(projectId, new float[]{1.0f, 0.05f, 0.0f}, 2);

        assertEquals(2, hits.size());
        assertEquals("docs/architecture.md", hits.getFirst().relativePath());
        assertEquals(FileCategory.DOCUMENTATION, hits.getFirst().category());
    }

    @Test
    void appliesUpdatesAndRemovalsByRelativePath() throws Exception {
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")),
                3);
        UUID projectId = UUID.randomUUID();

        index.rebuild(projectId, List.of(
                document("docs/architecture.md", FileCategory.DOCUMENTATION, "architecture", 1.0f, 0.0f, 0.0f),
                document("docs/database.md", FileCategory.DOCUMENTATION, "database", 0.0f, 1.0f, 0.0f)));

        index.applyChanges(
                projectId,
                List.of(document("docs/boundaries.md", FileCategory.DOCUMENTATION, "boundaries", 1.0f, 0.0f, 0.0f)),
                Set.of("docs/architecture.md"));

        List<SemanticSearchHit> hits = index.search(projectId, new float[]{1.0f, 0.0f, 0.0f}, 5);

        assertEquals("docs/boundaries.md", hits.getFirst().relativePath());
        assertFalse(hits.stream().anyMatch(hit -> hit.relativePath().equals("docs/architecture.md")));
    }

    @Test
    void closesAllHandlesAfterRebuildUpdateAndSearch() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("semantic-lifecycle-home"));
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, 3);
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(
                document("docs/a.md", FileCategory.DOCUMENTATION, "a", 1.0f, 0.0f, 0.0f)));
        index.applyChanges(projectId, List.of(
                document("docs/b.md", FileCategory.DOCUMENTATION, "b", 0.9f, 0.1f, 0.0f)), Set.of());
        index.search(projectId, new float[]{1.0f, 0.0f, 0.0f}, 5);

        deleteTree(paths.projectSemanticLuceneIndex(projectId));
        assertFalse(Files.exists(paths.projectSemanticLuceneIndex(projectId)));
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static SemanticVectorDocument document(
            String path,
            FileCategory category,
            String excerpt,
            float... vector) {
        return new SemanticVectorDocument(path, category, excerpt, vector);
    }
}
