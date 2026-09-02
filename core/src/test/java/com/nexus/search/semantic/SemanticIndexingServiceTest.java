package com.nexus.search.semantic;

import com.nexus.index.FileCategory;
import com.nexus.search.SearchDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void doesNotSplitSurrogatePairsAtEmbeddingOrExcerptBoundaries() throws Exception {
        CapturingSemanticIndex index = new CapturingSemanticIndex(3);
        String[] embeddedText = {null};
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "test/unicode-model";
            }

            @Override
            public int dimensions() {
                return 3;
            }

            @Override
            public float[] embed(String text) {
                embeddedText[0] = text;
                return new float[]{1.0f, 0.0f, 0.0f};
            }
        };

        String path = "docs/unicode.md";
        String header = "path: " + path + "\nlanguage: markdown\n";
        String prefix = "x".repeat(319);
        String content = prefix + "😀tail";
        SemanticIndexingService service = new SemanticIndexingService(
                provider,
                index,
                header.length() + 320,
                1);

        service.rebuild(UUID.randomUUID(), List.of(document(path, content)));

        assertEquals(header + prefix, embeddedText[0]);
        assertEquals(prefix, index.rebuilt.getFirst().excerpt());
        assertFalse(hasIsolatedSurrogate(embeddedText[0]));
        assertFalse(hasIsolatedSurrogate(index.rebuilt.getFirst().excerpt()));
    }

    private static boolean hasIsolatedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
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
