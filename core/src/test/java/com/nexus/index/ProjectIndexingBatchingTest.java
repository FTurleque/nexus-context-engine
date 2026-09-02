package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.LexicalSearchHit;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexingBatchingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rebuildsDerivedIndexInBoundedDocumentBatches() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(
                    projectRoot.resolve("doc-%d.md".formatted(index)),
                    "document-%d-%s\n".formatted(index, "x".repeat(20)));
        }

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "batch-demo");
        CapturingSearchIndex searchIndex = new CapturingSearchIndex();
        long batchBytes = 70L;

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(),
                searchIndex,
                List.of(),
                List.of(),
                null,
                Duration.ofSeconds(5),
                ProjectIndexLockManager.processLocalOnly(),
                100,
                batchBytes);

        IndexingReport report = service.index(project.id());

        assertEquals(5, report.changedFiles());
        assertEquals(1, searchIndex.rebuildCalls);
        assertEquals(0, searchIndex.rebuildDocuments);
        assertEquals(5, searchIndex.batches.stream().mapToInt(List::size).sum());
        assertTrue(searchIndex.batches.size() >= 3);
        assertTrue(searchIndex.batches.stream().allMatch(batch -> batchBytes(batch) <= batchBytes));
    }

    private static long batchBytes(List<SearchDocument> documents) {
        return documents.stream()
                .map(SearchDocument::content)
                .mapToLong(content -> content.getBytes(StandardCharsets.UTF_8).length)
                .sum();
    }

    private static final class CapturingSearchIndex implements SearchIndex {
        private int rebuildCalls;
        private int rebuildDocuments;
        private final List<List<SearchDocument>> batches = new ArrayList<>();

        @Override
        public void applyChanges(
                UUID projectId,
                List<SearchDocument> documents,
                Set<String> removedPaths) {
            if (!documents.isEmpty()) {
                batches.add(List.copyOf(documents));
            }
        }

        @Override
        public void rebuild(UUID projectId, List<SearchDocument> documents) {
            rebuildCalls++;
            rebuildDocuments += documents.size();
        }

        @Override
        public List<LexicalSearchHit> search(UUID projectId, String query, int limit) {
            return List.of();
        }
    }
}
