package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.ranking.SemanticHybridContextRanker;
import com.nexus.search.SearchSignals;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusApplicationSemanticConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultCompositionKeepsSemanticSearchDisabled() throws Exception {
        Path repository = repository("default-repository");
        Files.writeString(repository.resolve("docs/default.md"), "Cache coordination policy for local requests.");

        NexusApplication application = NexusApplication.create(new NexusPaths(temporaryDirectory.resolve("default-home")));
        ProjectDescriptor project = application.registerProject(repository, "default-semantic-disabled");
        application.index(project.id(), true, false);

        List<RankedCandidate> results = application.search(project.id(), "cache coordination", 5, true).results();

        assertFalse(results.isEmpty());
        assertTrue(results.stream().noneMatch(result -> result.components().containsKey(SearchSignals.SEMANTIC)));
        assertTrue(results.stream().noneMatch(result ->
                result.components().containsKey(SemanticHybridContextRanker.SEMANTIC_RRF_COMPONENT)));
    }

    @Test
    void enabledCompositionIndexesAndSearchesWithSemanticRrfFusion() throws Exception {
        Path repository = repository("semantic-repository");
        Files.writeString(
                repository.resolve("docs/cache-control.md"),
                "Coalesce simultaneous misses behind one in-flight load to prevent duplicate backend work.");
        Files.writeString(
                repository.resolve("docs/rendering.md"),
                "Render interface widgets with a responsive layout and accessible labels.");

        CountingSemanticProvider provider = new CountingSemanticProvider();
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("semantic-home")),
                SemanticSearchConfiguration.enabled(provider));
        ProjectDescriptor project = application.registerProject(repository, "semantic-enabled");
        application.index(project.id(), true, false);

        List<RankedCandidate> results = application.search(project.id(), "thundering herd mitigation", 5, true).results();

        assertFalse(results.isEmpty());
        RankedCandidate first = results.getFirst();
        assertEquals(repository.resolve("docs/cache-control.md"), first.candidate().path());
        assertTrue(first.components().containsKey(SemanticHybridContextRanker.SEMANTIC_RRF_COMPONENT));
        assertTrue(first.components().get(SemanticHybridContextRanker.SEMANTIC_RRF_COMPONENT) > 0.0d);
        assertTrue(first.reasons().stream().anyMatch(reason -> reason.contains("fusion RRF sémantique")));
        assertTrue(provider.calls() >= 3, "Deux documents et la requête doivent être vectorisés");
    }

    private Path repository(String name) throws IOException {
        Path repository = temporaryDirectory.resolve(name);
        Files.createDirectories(repository.resolve("docs"));
        return repository;
    }

    private static final class CountingSemanticProvider implements EmbeddingProvider {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String modelId() {
            return "test/semantic-composition";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("thundering herd") || normalized.contains("coalesce simultaneous misses")) {
                return new float[]{1.0f, 0.0f};
            }
            return new float[]{0.0f, 1.0f};
        }

        int calls() {
            return calls.get();
        }
    }
}
