package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NexusApplicationLongLivedLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void longLivedLexicalReadersRefreshRebuildAndReleaseFiles() throws Exception {
        Path repository = repository("lexical-repository");
        Path document = repository.resolve("docs/cache.md");
        Files.writeString(document, "Alpha cache coordination policy.");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("lexical-home"));
        NexusApplication application = NexusApplication.createLongLived(paths);
        ProjectDescriptor project = application.registerProject(repository, "lexical-long-lived");
        application.index(project.id(), true, false);

        assertFalse(application.search(project.id(), "alpha cache", 5, false).results().isEmpty());

        Files.writeString(document, "Beta retry coordination policy.");
        application.index(project.id(), false, false);
        assertFalse(application.search(project.id(), "beta retry", 5, false).results().isEmpty());

        application.index(project.id(), true, false);
        assertFalse(application.search(project.id(), "beta retry", 5, false).results().isEmpty());

        application.close();
        application.close();

        deleteRecursively(paths.projectLuceneIndex(project.id()));
        assertFalse(Files.exists(paths.projectLuceneIndex(project.id())));
    }

    @Test
    void longLivedSemanticReadersReleaseDerivedIndexAfterHotSearchAndRebuild() throws Exception {
        Path repository = repository("semantic-repository");
        Files.writeString(repository.resolve("docs/cache.md"), "Coalesce cache misses behind one loader.");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        SemanticSearchConfiguration configuration =
                SemanticSearchConfiguration.enabled(new DeterministicEmbeddingProvider());
        NexusApplication application = NexusApplication.createLongLived(paths, configuration);
        ProjectDescriptor project = application.registerProject(repository, "semantic-long-lived");
        application.index(project.id(), true, false);

        assertFalse(application.search(project.id(), "cache loader", 5, false).results().isEmpty());
        application.index(project.id(), true, false);
        assertFalse(application.search(project.id(), "cache loader", 5, false).results().isEmpty());

        application.close();

        deleteRecursively(paths.projectSemanticLuceneIndex(project.id()));
        assertFalse(Files.exists(paths.projectSemanticLuceneIndex(project.id())));
    }

    private Path repository(String name) throws IOException {
        Path repository = temporaryDirectory.resolve(name);
        Files.createDirectories(repository.resolve("docs"));
        return repository;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static final class DeterministicEmbeddingProvider implements EmbeddingProvider {

        @Override
        public String modelId() {
            return "test/long-lived-lifecycle";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            return normalized.contains("cache")
                    ? new float[]{1.0f, 0.0f}
                    : new float[]{0.0f, 1.0f};
        }
    }
}
