package com.nexus.search.semantic;

import com.nexus.index.FileCategory;
import com.nexus.search.SearchDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticIndexingServiceTest {

    @Test
    void vectorizesDocumentsForRebuildAndIncrementalUpdates() throws Exception {
        CapturingSemanticIndex index = new CapturingSemanticIndex(3);
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "test/semantic-model";
            }

            @Override
            public int dimensions() {
                return 3;
            }

            @Override
            public float[] embed(String text) {
                return text.contains("boundary")
                        ? new float[]{1.0f, 0.0f, 0.0f}
                        : new float[]{0.0f, 1.0f, 0.0f};
            }
        };
        SemanticIndexingService service = new SemanticIndexingService(provider, index);
        UUID projectId = UUID.randomUUID();

        service.rebuild(projectId, List.of(document("docs/architecture.md", "domain boundary architecture")));

        assertEquals("test/semantic-model", service.modelId());
        assertEquals(1, index.rebuilt.size());
        assertEquals("docs/architecture.md", index.rebuilt.getFirst().relativePath());
        assertEquals(1.0f, index.rebuilt.getFirst().vector()[0]);

        service.applyChanges(
                projectId,
                List.of(document("docs/database.md", "database migration")),
                Set.of("docs/architecture.md"));

        assertEquals(1, index.updated.size());
        assertEquals("docs/database.md", index.updated.getFirst().relativePath());
        assertEquals(Set.of("docs/architecture.md"), index.removed);
        assertTrue(index.updated.getFirst().excerpt().contains("database migration"));
    }

    private static SearchDocument document(String path, String content) {
        return new SearchDocument(path, "markdown", FileCategory.DOCUMENTATION, content, List.of());
    }

    private static final class CapturingSemanticIndex implements SemanticSearchIndex {

        private final int dimensions;
        private List<SemanticVectorDocument> rebuilt = new ArrayList<>();
        private List<SemanticVectorDocument> updated = new ArrayList<>();
        private Set<String> removed = Set.of();

        private CapturingSemanticIndex(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        @Override
        public void rebuild(UUID projectId, List<SemanticVectorDocument> documents) {
            rebuilt = List.copyOf(documents);
        }

        @Override
        public void applyChanges(
                UUID projectId,
                List<SemanticVectorDocument> documents,
                Set<String> removedRelativePaths) {
            updated = List.copyOf(documents);
            removed = Set.copyOf(removedRelativePaths);
        }

        @Override
        public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) {
            return List.of();
        }
    }
}
