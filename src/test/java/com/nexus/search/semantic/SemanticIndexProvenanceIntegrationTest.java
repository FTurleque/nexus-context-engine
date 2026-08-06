package com.nexus.search.semantic;

import com.nexus.config.NexusPaths;
import com.nexus.index.CanonicalIndexFingerprint;
import com.nexus.index.IndexRepository;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticIndexProvenanceIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rebuildsAfterSemanticWasDisabledDuringCanonicalChangesAndAfterModelChange() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class App { void run() {} }\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "demo");

        TestEmbeddingProvider modelA = new TestEmbeddingProvider("model-a");
        LuceneSemanticSearchIndex semanticIndex = new LuceneSemanticSearchIndex(paths, modelA.dimensions());
        ProjectIndexingService semanticServiceA = service(
                paths,
                projectRepository,
                indexRepository,
                new SemanticIndexingService(modelA, semanticIndex));
        semanticServiceA.index(project.id());

        String initialFingerprint = fingerprint(indexRepository, project);
        assertTrue(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(initialFingerprint, modelA)));

        // Simule un runtime avec NEXUS_SEMANTIC_ENABLED=false pendant que le
        // projet évolue : SQLite/Lucene lexical sont mis à jour, pas les vecteurs.
        Files.writeString(source, "package demo; class App { void run() {} void stop() {} }\n");
        service(paths, projectRepository, indexRepository, null).index(project.id());

        String changedFingerprint = fingerprint(indexRepository, project);
        assertFalse(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelA)));

        int embeddingsBeforeRecovery = modelA.embeddings;
        ProjectIndexingService recoveredSemanticService = service(
                paths,
                projectRepository,
                indexRepository,
                new SemanticIndexingService(modelA, semanticIndex));
        recoveredSemanticService.index(project.id());

        assertTrue(modelA.embeddings > embeddingsBeforeRecovery);
        assertTrue(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelA)));

        // Même provider et même dimension, mais autre modèle : les espaces
        // vectoriels ne sont pas supposés compatibles et doivent être reconstruits.
        TestEmbeddingProvider modelB = new TestEmbeddingProvider("model-b");
        assertFalse(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelB)));

        service(
                paths,
                projectRepository,
                indexRepository,
                new SemanticIndexingService(modelB, semanticIndex)).index(project.id());

        assertTrue(modelB.embeddings > 0);
        assertTrue(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelB)));
        assertFalse(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelA)));
    }

    private static ProjectIndexingService service(
            NexusPaths paths,
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            SemanticIndexingService semanticIndexingService) {
        return new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths),
                List.of(),
                List.of(),
                semanticIndexingService);
    }

    private static String fingerprint(IndexRepository repository, ProjectDescriptor project) {
        return CanonicalIndexFingerprint.fromIndexedFiles(repository.findFiles(project.id()));
    }

    private static final class TestEmbeddingProvider implements EmbeddingProvider {
        private final String modelId;
        private int embeddings;

        private TestEmbeddingProvider(String modelId) {
            this.modelId = modelId;
        }

        @Override
        public String providerId() {
            return "test-provider";
        }

        @Override
        public String modelId() {
            return modelId;
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public float[] embed(String text) {
            embeddings++;
            return new float[]{1.0f, 0.25f, 0.1f};
        }
    }
}
