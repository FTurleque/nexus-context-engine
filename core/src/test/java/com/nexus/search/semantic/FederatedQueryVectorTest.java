package com.nexus.search.semantic;

import com.nexus.context.ContextBuilder;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextMaterializationBudget;
import com.nexus.context.ContextRequest;
import com.nexus.context.FederatedContextService;
import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.FederatedSearchService;
import com.nexus.search.SearchQueryContext;
import com.nexus.search.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUD-PRF-02 : un seul vecteur de requête par requête fédérée. */
class FederatedQueryVectorTest {

    private static final String SECRET = "ghp_abcdefghijklmnopqrstuvwxyz123456";

    @TempDir
    Path root;

    @Test
    void federatedSearchEmbedsTheQueryOnceForThreeProjects() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        SearchService searchService = searchService(provider, new ArrayList<>());

        new FederatedSearchService(searchService).search(projects(3), "authentification", 5, false);

        assertEquals(1, provider.calls.get());
    }

    @Test
    void federatedSearchTransmitsTheRedactedQueryToTheProvider() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        SearchService searchService = searchService(provider, new ArrayList<>());

        new FederatedSearchService(searchService)
                .search(projects(3), "debug token=" + SECRET, 5, false);

        assertEquals(1, provider.calls.get());
        assertFalse(provider.lastText.contains(SECRET));
        assertTrue(provider.lastText.contains("[REDACTED]"));
    }

    @Test
    void unavailableProviderIsCalledOnceAndDegradesEveryProjectWithTheSameDiagnostic() throws Exception {
        CountingProvider provider = new CountingProvider(true);
        List<String> reports = new ArrayList<>();
        SearchService searchService = searchService(provider, reports);
        List<ProjectDescriptor> projects = projects(3);

        new FederatedSearchService(searchService).search(projects, "authentification", 5, false);

        assertEquals(1, provider.calls.get());
        assertEquals(3, reports.size());
        for (int index = 0; index < projects.size(); index++) {
            String expected = "semantic-search degraded: code=embedding_provider_unavailable project="
                    + projects.get(index).id() + " provider=counting-fixture";
            assertTrue(reports.get(index).startsWith(expected), reports.get(index));
        }
    }

    @Test
    void federatedContextSharesOneQueryContextAcrossProjects() {
        Map<SearchQueryContext, Integer> contexts = new IdentityHashMap<>();
        ContextBuilder builder = new ContextBuilder() {
            @Override
            public ContextBundle build(ContextRequest request) {
                throw new AssertionError("le contexte de requête partagé doit être transmis");
            }

            @Override
            public ContextBundle build(
                    ContextRequest request,
                    ContextMaterializationBudget materializationBudget,
                    ContextDiscoveryBudget discoveryBudget,
                    SearchQueryContext queryContext) {
                contexts.merge(queryContext, 1, Integer::sum);
                return new ContextBundle(List.of(), request.tokenBudget(), 0, List.of(), Map.of());
            }
        };

        new FederatedContextService(builder).build(
                projects(3), "task", 300, Set.of(), Map.of(), false);

        assertEquals(1, contexts.size());
        assertEquals(3, contexts.values().iterator().next());
    }

    @Test
    void sharedComputationRunsOnceAndFailuresAreNotMemoized() throws IOException {
        SearchQueryContext context = SearchQueryContext.create();
        AtomicInteger runs = new AtomicInteger();

        assertEquals("v", context.shared("k", String.class, () -> {
            runs.incrementAndGet();
            return "v";
        }));
        assertEquals("v", context.shared("k", String.class, () -> {
            runs.incrementAndGet();
            return "autre";
        }));
        assertEquals(1, runs.get());

        try {
            context.shared("failing", String.class, () -> {
                throw new IOException("boom");
            });
        } catch (IOException expected) {
            assertEquals("boom", expected.getMessage());
        }
        assertEquals("ok", context.shared("failing", String.class, () -> "ok"));
    }

    private SearchService searchService(CountingProvider provider, List<String> reports) {
        SemanticSearchStrategy strategy = new SemanticSearchStrategy(
                provider,
                new EmptyIndex(),
                null,
                SemanticIndexingService.defaultProfileId(),
                (message, failure) -> reports.add(message));
        return new SearchService(List.of(strategy), List.of(), (request, candidates) -> List.of());
    }

    private List<ProjectDescriptor> projects(int count) {
        List<ProjectDescriptor> projects = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            projects.add(new ProjectDescriptor(
                    UUID.randomUUID(),
                    "project-" + index,
                    root.resolve("project-" + index),
                    ProjectSourceType.LOCAL,
                    Set.of("java"),
                    Set.of(),
                    Instant.now(),
                    IndexStatus.READY));
        }
        return projects;
    }

    private static final class CountingProvider implements EmbeddingProvider {
        private final boolean unavailable;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String lastText;

        private CountingProvider(boolean unavailable) {
            this.unavailable = unavailable;
        }

        @Override
        public String modelId() {
            return "counting-fixture";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public float[] embed(String text) throws IOException {
            calls.incrementAndGet();
            lastText = text;
            if (unavailable) {
                throw new EmbeddingProviderUnavailableException("indisponible", null);
            }
            return new float[]{1.0f, 0.0f};
        }
    }

    private static final class EmptyIndex implements SemanticSearchIndex {
        @Override
        public int dimensions() {
            return 2;
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
            return List.of();
        }
    }
}
