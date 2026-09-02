package com.nexus.search.semantic;

import com.nexus.index.FileCategory;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticSearchStrategyTest {

    private static final UUID FIXED_PROJECT_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsSemanticHitsToStandardSearchCandidates() throws Exception {
        EmbeddingProvider provider = provider(3, new float[]{1.0f, 0.0f, 0.0f});
        SemanticSearchIndex index = index(3, List.of(
                new SemanticSearchHit(
                        "docs/architecture.md",
                        FileCategory.DOCUMENTATION,
                        "Découplage du domaine et des adaptateurs",
                        0.92d),
                new SemanticSearchHit(
                        ".github/copilot-instructions.md",
                        FileCategory.INSTRUCTION,
                        "instructions",
                        0.99d)));

        SemanticSearchStrategy strategy = new SemanticSearchStrategy(provider, index);
        List<SearchCandidate> candidates = strategy.search(project(), "séparer le domaine de l'infrastructure", 5);

        assertEquals(1, candidates.size());
        SearchCandidate candidate = candidates.getFirst();
        assertEquals("file:docs/architecture.md", candidate.id());
        assertEquals(CandidateType.DOCUMENTATION, candidate.type());
        assertEquals(temporaryDirectory.resolve("docs/architecture.md"), candidate.path());
        assertEquals(0.92d, candidate.signals().get(SearchSignals.SEMANTIC), 0.000001d);
    }

    @Test
    void degradesWhenEmbeddingProviderIsUnavailableWithDeterministicDiagnostic() throws Exception {
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "ollama/fixture";
            }

            @Override
            public int dimensions() {
                return 3;
            }

            @Override
            public float[] embed(String text) throws IOException {
                throw new IOException("platform-specific transport failure");
            }
        };
        AtomicReference<String> diagnostic = new AtomicReference<>();
        SemanticSearchStrategy strategy = new SemanticSearchStrategy(
                provider,
                index(3, List.of()),
                null,
                SemanticIndexingService.defaultProfileId(),
                (message, failure) -> diagnostic.set(message));

        List<SearchCandidate> candidates = strategy.search(project(FIXED_PROJECT_ID), "query", 5);

        assertEquals(List.of(), candidates);
        assertEquals(
                "semantic-search degraded: code=embedding_provider_unavailable project=" + FIXED_PROJECT_ID
                        + " provider=ollama/fixture; fallback=lexical-symbolic; retry=automatic",
                diagnostic.get());
    }

    @Test
    void degradesWhenSemanticIndexReadFailsWithExplicitRebuildDiagnostic() throws Exception {
        EmbeddingProvider provider = provider(3, new float[]{1.0f, 0.0f, 0.0f});
        SemanticSearchIndex unavailableIndex = new SemanticSearchIndex() {
            @Override
            public int dimensions() {
                return 3;
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
            public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) throws IOException {
                throw new IOException("corrupt segments fixture");
            }
        };
        AtomicReference<String> diagnostic = new AtomicReference<>();
        SemanticSearchStrategy strategy = new SemanticSearchStrategy(
                provider,
                unavailableIndex,
                null,
                SemanticIndexingService.defaultProfileId(),
                (message, failure) -> diagnostic.set(message));

        List<SearchCandidate> candidates = strategy.search(project(FIXED_PROJECT_ID), "query", 5);

        assertEquals(List.of(), candidates);
        assertEquals(
                "semantic-search degraded: code=semantic_index_unavailable project=" + FIXED_PROJECT_ID
                        + "; fallback=lexical-symbolic; recovery=\"nexus index "
                        + FIXED_PROJECT_ID + " --rebuild\"",
                diagnostic.get());
    }

    @Test
    void rejectsDimensionMismatchAtCompositionTime() {
        EmbeddingProvider provider = provider(3, new float[]{1.0f, 0.0f, 0.0f});
        SemanticSearchIndex index = index(4, List.of());

        assertThrows(IllegalArgumentException.class, () -> new SemanticSearchStrategy(provider, index));
    }

    @Test
    void rejectsUnexpectedVectorDimensionReturnedByProvider() {
        EmbeddingProvider provider = provider(3, new float[]{1.0f, 0.0f});
        SemanticSearchIndex index = index(3, List.of());
        SemanticSearchStrategy strategy = new SemanticSearchStrategy(provider, index);

        assertThrows(IOException.class, () -> strategy.search(project(), "query", 5));
    }

    private ProjectDescriptor project() {
        return project(UUID.randomUUID());
    }

    private ProjectDescriptor project(UUID projectId) {
        return new ProjectDescriptor(
                projectId,
                "semantic-test",
                temporaryDirectory,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                Instant.now(),
                IndexStatus.READY);
    }

    private static EmbeddingProvider provider(int dimensions, float[] vector) {
        return new EmbeddingProvider() {
            @Override
            public String modelId() {
                return "test-model";
            }

            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public float[] embed(String text) {
                return vector.clone();
            }
        };
    }

    private static SemanticSearchIndex index(int dimensions, List<SemanticSearchHit> hits) {
        return new SemanticSearchIndex() {
            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public void rebuild(UUID projectId, List<SemanticVectorDocument> documents) {
                // no-op for strategy tests
            }

            @Override
            public void applyChanges(
                    UUID projectId,
                    List<SemanticVectorDocument> documents,
                    Set<String> removedRelativePaths) {
                // no-op for strategy tests
            }

            @Override
            public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) {
                return hits;
            }
        };
    }
}
