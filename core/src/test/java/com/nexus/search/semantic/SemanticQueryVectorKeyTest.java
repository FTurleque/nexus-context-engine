package com.nexus.search.semantic;

import com.nexus.index.FileCategory;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.SearchQueryContext;
import com.nexus.search.candidate.SearchCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** AUD-QUA-23 : un vecteur de requête partagé n'est réutilisé que pour le même texte de requête. */
class SemanticQueryVectorKeyTest {

    @TempDir
    Path root;

    @Test
    void aContextReusedForTwoQueriesEmbedsEachQueryAndReturnsItsOwnResults() throws Exception {
        TextProvider provider = new TextProvider();
        SemanticSearchStrategy strategy = strategy(provider);
        ProjectDescriptor project = project(0);
        SearchQueryContext context = SearchQueryContext.create();

        List<SearchCandidate> parseConfig = strategy.search(project, "parseConfig", 5, context);
        List<SearchCandidate> renderError = strategy.search(project, "renderError", 5, context);

        assertEquals(List.of("parseConfig", "renderError"), provider.texts);
        assertEquals(List.of("file:parse-config.java"), ids(parseConfig));
        assertEquals(List.of("file:other.java"), ids(renderError));
        assertNotEquals(ids(parseConfig), ids(renderError));
    }

    @Test
    void theSameQueryOverThreeProjectsEmbedsOnce() throws Exception {
        TextProvider provider = new TextProvider();
        SemanticSearchStrategy strategy = strategy(provider);
        SearchQueryContext context = SearchQueryContext.create();

        for (int index = 0; index < 3; index++) {
            assertEquals(List.of("file:parse-config.java"),
                    ids(strategy.search(project(index), "parseConfig", 5, context)));
        }

        assertEquals(List.of("parseConfig"), provider.texts);
    }

    @Test
    void theKeyNamesTheProviderTheModelAndTheRedactedText() {
        SemanticSearchStrategy strategy = strategy(new TextProvider());

        assertEquals("semantic-query-vector|local|text-fixture|parseConfig", strategy.queryVectorKey("parseConfig"));
        assertNotEquals(strategy.queryVectorKey("parseConfig"), strategy.queryVectorKey("renderError"));
    }

    private static SemanticSearchStrategy strategy(TextProvider provider) {
        return new SemanticSearchStrategy(
                provider,
                new VectorEchoIndex(),
                null,
                SemanticIndexingService.defaultProfileId(),
                (message, failure) -> {
                    throw new AssertionError(message, failure);
                });
    }

    private ProjectDescriptor project(int index) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "project-" + index,
                root.resolve("project-" + index),
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                Instant.EPOCH,
                IndexStatus.READY);
    }

    private static List<String> ids(List<SearchCandidate> candidates) {
        return candidates.stream().map(SearchCandidate::id).toList();
    }

    /** Vecteur propre au texte : {@code parseConfig} pointe sur un axe, tout autre texte sur l'autre. */
    private static final class TextProvider implements EmbeddingProvider {
        private final List<String> texts = new ArrayList<>();

        @Override
        public String providerId() {
            return "local";
        }

        @Override
        public String modelId() {
            return "text-fixture";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public synchronized float[] embed(String text) {
            texts.add(text);
            return text.equals("parseConfig") ? new float[] {1.0f, 0.0f} : new float[] {0.0f, 1.0f};
        }
    }

    /** Rend un résultat qui dépend du vecteur reçu. */
    private static final class VectorEchoIndex implements SemanticSearchIndex {
        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public void rebuild(UUID projectId, List<SemanticVectorDocument> documents) {
            // Index en lecture seule pour ce test.
        }

        @Override
        public void applyChanges(UUID projectId, List<SemanticVectorDocument> documents, Set<String> removed) {
            // Index en lecture seule pour ce test.
        }

        @Override
        public List<SemanticSearchHit> search(UUID projectId, float[] queryVector, int limit) {
            String path = queryVector[0] > 0.5f ? "parse-config.java" : "other.java";
            return List.of(new SemanticSearchHit(path, FileCategory.SOURCE, "excerpt", 0.9d));
        }
    }
}
