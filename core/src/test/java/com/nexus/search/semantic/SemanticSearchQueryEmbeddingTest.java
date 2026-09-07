package com.nexus.search.semantic;

import com.nexus.index.FileCategory;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SemanticSearchQueryEmbeddingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void routesSearchTextThroughQuerySpecificEmbeddingChannel() throws Exception {
        AtomicBoolean genericEmbeddingCalled = new AtomicBoolean();
        AtomicReference<String> embeddedQuery = new AtomicReference<>();
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "query-aware-fixture";
            }

            @Override
            public int dimensions() {
                return 1;
            }

            @Override
            public float[] embed(String text) {
                genericEmbeddingCalled.set(true);
                return new float[]{1.0f};
            }

            @Override
            public float[] embedQuery(String query) {
                embeddedQuery.set(query);
                return new float[]{1.0f};
            }
        };
        SemanticSearchIndex index = new SemanticSearchIndex() {
            @Override
            public int dimensions() {
                return 1;
            }

            @Override
            public void rebuild(UUID projectId, List<SemanticVectorDocument> documents) {
                // no-op
            }

            @Override
            public void applyChanges(
                    UUID projectId,
                    List<SemanticVectorDocument> documents,
                    Set<String> removedRelativePaths) {
                // no-op
            }

            @Override
            public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) {
                return List.of(new SemanticSearchHit(
                        "docs/semantic.md",
                        FileCategory.DOCUMENTATION,
                        "semantic fixture",
                        0.9d));
            }
        };
        SemanticSearchStrategy strategy = new SemanticSearchStrategy(provider, index);
        ProjectDescriptor project = new ProjectDescriptor(
                UUID.randomUUID(),
                "query-aware",
                temporaryDirectory,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                Instant.now(),
                IndexStatus.READY);

        strategy.search(project, "retrieve semantic documentation", 3);

        assertFalse(genericEmbeddingCalled.get());
        assertEquals("retrieve semantic documentation", embeddedQuery.get());
    }
}
