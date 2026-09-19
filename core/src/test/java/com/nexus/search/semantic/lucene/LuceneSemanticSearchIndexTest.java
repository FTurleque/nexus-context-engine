package com.nexus.search.semantic.lucene;

import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.semantic.SemanticSearchHit;
import com.nexus.search.semantic.SemanticVectorDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneSemanticSearchIndexTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void independentPersistentReaderFollowsRepeatedRebuildsAndEmptyCommits() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("independent-reader"));
        UUID project = UUID.randomUUID();
        LuceneSemanticSearchIndex writer = new LuceneSemanticSearchIndex(paths, 3);
        try (var reader = new PersistentLuceneSemanticSearchIndex(paths, 3)) {
            writer.rebuild(project, List.of(document("before.java", FileCategory.SOURCE, "before", 1, 0, 0)));
            assertEquals("before.java", reader.search(project, new float[]{1, 0, 0}, 1).getFirst().relativePath());
            for (int iteration = 0; iteration < 3; iteration++) {
                String path = "after-" + iteration + ".java";
                writer.rebuild(project, List.of(document(path, FileCategory.SOURCE, "after", 1, 0, 0)));
                assertEquals(path, reader.search(project, new float[]{1, 0, 0}, 1).getFirst().relativePath());
            }
            writer.rebuild(project, List.of());
            assertEquals(List.of(), reader.search(project, new float[]{1, 0, 0}, 1));
        }
        deleteTree(paths.projectSemanticLuceneIndex(project));
    }

    @Test
    void independentReaderFollowsRecoveryFromCorruptCommitAndCanThenWrite() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("independent-recovery"));
        UUID project = UUID.randomUUID();
        LuceneSemanticSearchIndex writer = new LuceneSemanticSearchIndex(paths, 3);
        try (var reader = new PersistentLuceneSemanticSearchIndex(paths, 3)) {
            writer.rebuild(project, List.of(document("before.java", FileCategory.SOURCE, "before", 1, 0, 0)));
            assertEquals("before.java", reader.search(project, new float[]{1, 0, 0}, 1).getFirst().relativePath());
            corruptCommitFiles(paths.projectSemanticLuceneIndex(project));
            writer.rebuild(project, List.of(document("recovered.java", FileCategory.SOURCE, "after", 1, 0, 0)));
            // An idle cached reader may be followed by a mutation, not only by a search.
            reader.applyChanges(project,
                    List.of(document("updated.java", FileCategory.SOURCE, "updated", 1, 0, 0)),
                    Set.of("recovered.java"));
            assertEquals("updated.java", reader.search(project, new float[]{1, 0, 0}, 1).getFirst().relativePath());
            assertEquals("updated.java", writer.search(project, new float[]{1, 0, 0}, 1).getFirst().relativePath());
        }
        deleteTree(paths.projectSemanticLuceneIndex(project));
    }

    @Test
    void rebuildWithNewDimensionsDoesNotServeThePreviousVectorSpace() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("dimension-rebuild"));
        UUID project = UUID.randomUUID();
        LuceneSemanticSearchIndex original = new LuceneSemanticSearchIndex(paths, 2);
        try (var oldReader = new PersistentLuceneSemanticSearchIndex(paths, 2)) {
            original.rebuild(project, List.of(document("before.java", FileCategory.SOURCE, "before", 1, 0)));
            oldReader.search(project, new float[]{1, 0}, 1);
            LuceneSemanticSearchIndex resized = new LuceneSemanticSearchIndex(paths, 3);
            resized.rebuild(project, List.of(document("after.java", FileCategory.SOURCE, "after", 1, 0, 0)));
            assertEquals("after.java", resized.search(project, new float[]{1, 0, 0}, 1).getFirst().relativePath());
            assertThrows(IllegalArgumentException.class, () -> oldReader.search(project, new float[]{1, 0}, 1));
        }
    }

    @Test
    void failedRebuildDoesNotPublishPartialDocuments() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("failed-rebuild"));
        UUID project = UUID.randomUUID();
        LuceneSemanticSearchIndex writer = new LuceneSemanticSearchIndex(paths, 3);
        writer.rebuild(project, List.of(document("before.java", FileCategory.SOURCE, "before", 1, 0, 0)));
        List<SemanticVectorDocument> invalidDocuments = List.of(
                document("partial.java", FileCategory.SOURCE, "partial", 1, 0, 0),
                document("invalid.java", FileCategory.SOURCE, "invalid", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> writer.rebuild(project, invalidDocuments));
        assertEquals("before.java", writer.search(project, new float[]{1, 0, 0}, 2).getFirst().relativePath());
        assertEquals(1, writer.search(project, new float[]{1, 0, 0}, 2).size());
    }

    @Test
    void refusesRecoveryPointersOutsideTheSemanticDirectory() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("invalid-pointer"));
        UUID project = UUID.randomUUID();
        Path root = paths.projectSemanticLuceneIndex(project);
        paths.ensurePrivateDirectory(root);
        Files.writeString(root.resolve("current-generation"), "../outside");
        LuceneSemanticSearchIndex writer = new LuceneSemanticSearchIndex(paths, 3);
        assertThrows(IOException.class, () -> writer.search(project, new float[]{1, 0, 0}, 1));
    }

    @Test
    void removesOnlyStaleInactiveRecoveryGenerations() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("recovery-gc"));
        UUID project = UUID.randomUUID();
        Path root = paths.projectSemanticLuceneIndex(project);
        paths.ensurePrivateDirectory(root);

        Path active = root.resolve("recovery-" + UUID.randomUUID());
        Path stale = root.resolve("recovery-" + UUID.randomUUID());
        Path recent = root.resolve("recovery-" + UUID.randomUUID());
        Path unrelated = root.resolve("manual-backup");
        paths.ensurePrivateDirectory(active);
        paths.ensurePrivateDirectory(stale);
        paths.ensurePrivateDirectory(recent);
        paths.ensurePrivateDirectory(unrelated);
        Files.writeString(stale.resolve("marker"), "stale");

        Instant now = Instant.now();
        Files.setLastModifiedTime(active, FileTime.from(now.minus(Duration.ofDays(3))));
        Files.setLastModifiedTime(stale, FileTime.from(now.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofHours(1))));

        LuceneSemanticSearchIndex.cleanupRecoveryGenerations(
                root,
                active,
                FileTime.from(now.minus(Duration.ofHours(24))));

        assertTrue(Files.isDirectory(active), "the active generation must never be garbage collected");
        assertFalse(Files.exists(stale), "stale inactive recovery generations must be removed");
        assertTrue(Files.isDirectory(recent), "recent inactive generations remain available to old readers");
        assertTrue(Files.isDirectory(unrelated), "non-recovery directories must not be touched");
    }

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
    void explicitRebuildRecoversPhysicallyCorruptIndex() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("semantic-corruption-home"));
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, 3);
        UUID projectId = UUID.randomUUID();
        index.rebuild(projectId, List.of(
                document("docs/before.md", FileCategory.DOCUMENTATION, "before", 1.0f, 0.0f, 0.0f)));

        corruptCommitFiles(paths.projectSemanticLuceneIndex(projectId));

        assertThrows(
                IOException.class,
                () -> index.search(projectId, new float[]{1.0f, 0.0f, 0.0f}, 5));

        index.rebuild(projectId, List.of(
                document("docs/recovered.md", FileCategory.DOCUMENTATION, "recovered", 1.0f, 0.0f, 0.0f)));
        List<SemanticSearchHit> recovered =
                index.search(projectId, new float[]{1.0f, 0.0f, 0.0f}, 5);

        assertEquals(1, recovered.size());
        assertEquals("docs/recovered.md", recovered.getFirst().relativePath());
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

    private static void corruptCommitFiles(Path indexPath) throws IOException {
        try (var files = Files.list(indexPath)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString().startsWith("segments_"))
                    .toList()) {
                Files.write(
                        file,
                        "corrupt-semantic-index".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        }
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
