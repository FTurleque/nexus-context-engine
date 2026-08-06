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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticIndexProvenanceIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rebuildsWheneverPersistedSemanticProvenanceIsIncompatible() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class App { void run() {} }\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "demo");

        TestEmbeddingProvider modelA = new TestEmbeddingProvider("model-a", 3);
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
        TestEmbeddingProvider modelB = new TestEmbeddingProvider("model-b", 3);
        assertFalse(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelB)));

        ProjectDescriptor readyProject = projectRepository.findById(project.id()).orElseThrow();
        SemanticSearchStrategy staleStrategy = new SemanticSearchStrategy(
                modelB,
                semanticIndex,
                indexRepository);
        assertTrue(staleStrategy.search(readyProject, "run application", 5).isEmpty());
        assertEquals(0, modelB.embeddings);

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

        // Même provider/modèle/dimension mais autre profil de préparation du
        // texte : le manifeste doit également forcer un rebuild complet.
        SemanticIndexingService compactProfile = new SemanticIndexingService(
                modelB,
                semanticIndex,
                4_000);
        assertFalse(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(
                        changedFingerprint,
                        modelB,
                        compactProfile.profileId())));
        int embeddingsBeforeProfileChange = modelB.embeddings;
        service(paths, projectRepository, indexRepository, compactProfile).index(project.id());
        assertTrue(modelB.embeddings > embeddingsBeforeProfileChange);
        assertTrue(semanticIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(
                        changedFingerprint,
                        modelB,
                        compactProfile.profileId())));

        // Une nouvelle dimension utilise un nouvel index writer sur le même
        // chemin et doit remplacer proprement l'ancien espace vectoriel.
        TestEmbeddingProvider modelC = new TestEmbeddingProvider("model-c", 4);
        LuceneSemanticSearchIndex resizedIndex = new LuceneSemanticSearchIndex(paths, modelC.dimensions());
        assertFalse(resizedIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelC)));
        service(
                paths,
                projectRepository,
                indexRepository,
                new SemanticIndexingService(modelC, resizedIndex)).index(project.id());
        assertTrue(modelC.embeddings > 0);
        assertTrue(resizedIndex.isCompatible(
                project.id(),
                SemanticIndexProvenance.current(changedFingerprint, modelC)));
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
        private final int dimensions;
        private int embeddings;

        private TestEmbeddingProvider(String modelId, int dimensions) {
            this.modelId = modelId;
            this.dimensions = dimensions;
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
            return dimensions;
        }

        @Override
        public float[] embed(String text) {
            embeddings++;
            float[] vector = new float[dimensions];
            Arrays.fill(vector, 0.1f);
            vector[0] = 1.0f;
            return vector;
        }
    }
}
