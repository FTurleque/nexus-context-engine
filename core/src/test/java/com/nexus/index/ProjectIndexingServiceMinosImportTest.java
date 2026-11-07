package com.nexus.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexus.config.NexusPaths;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.index.service.ProjectIndexingService;
import com.nexus.index.service.ProjectIndexingServices;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L'import MINOS passe par {@link ProjectIndexingService} et prend les mêmes verrous que l'indexation. */
class ProjectIndexingServiceMinosImportTest {

    /** Borne de sûreté d'une attente de test ; aucune assertion ne porte sur cette durée. */
    private static final Duration SAFETY_CAP = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesAnImportWhileTheSameProjectIsBeingIndexed() throws Exception {
        CountDownLatch analysing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture fixture = fixture(new BlockingAnalyzer(analysing, release));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IndexingReport> indexing = executor.submit(() -> fixture.service().index(fixture.project().id()));
            assertTrue(analysing.await(SAFETY_CAP.toSeconds(), TimeUnit.SECONDS), "L'indexation n'a pas démarré");

            UUID projectId = fixture.project().id();
            String payload = payload(fixture.project());
            IllegalStateException importRefusal = assertThrows(IllegalStateException.class,
                    () -> fixture.service().importExternalCodeIntelligence(projectId, payload));
            IllegalStateException indexRefusal = assertThrows(IllegalStateException.class,
                    () -> fixture.service().index(projectId));

            assertTrue(importRefusal.getMessage().contains("Une indexation est déjà en cours"),
                    importRefusal.getMessage());
            assertEquals(indexRefusal.getMessage(), importRefusal.getMessage());

            release.countDown();
            indexing.get(SAFETY_CAP.toSeconds(), TimeUnit.SECONDS);

            // Le refus a libéré le verrou single-flight : l'import passe une fois l'indexation terminée.
            CodeIntelligenceSnapshot snapshot = fixture.service().importExternalCodeIntelligence(projectId, payload);
            assertEquals(1, snapshot.symbols().size());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refusesAnImportHeldByAnotherProcessWithTheSameDiagnosticAsIndexing() throws Exception {
        Fixture fixture = fixture(new BlockingAnalyzer(new CountDownLatch(1), new CountDownLatch(0)));
        fixture.service().index(fixture.project().id());
        UUID projectId = fixture.project().id();
        String payload = payload(fixture.project());

        ProjectIndexLockManager secondOwner = ProjectIndexLockManager.fileBacked(fixture.paths());
        try (ProjectIndexLockManager.LockHandle ignored = secondOwner.acquire(projectId)) {
            IllegalStateException importRefusal = assertThrows(IllegalStateException.class,
                    () -> fixture.service().importExternalCodeIntelligence(projectId, payload));
            IllegalStateException indexRefusal = assertThrows(IllegalStateException.class,
                    () -> fixture.service().index(projectId));

            assertTrue(importRefusal.getMessage().contains("mutation d'index"), importRefusal.getMessage());
            assertEquals(indexRefusal.getMessage(), importRefusal.getMessage());
        }
    }

    @Test
    void requiresAReadyProject() throws Exception {
        Fixture fixture = fixture(new BlockingAnalyzer(new CountDownLatch(1), new CountDownLatch(0)));
        UUID projectId = fixture.project().id();
        String payload = payload(fixture.project());

        assertThrows(IllegalStateException.class,
                () -> fixture.service().importExternalCodeIntelligence(projectId, payload));
    }

    private Fixture fixture(LanguageAnalyzer analyzer) throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(projectRoot.resolve("App.java"), "class App {}\n");
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projects).register(projectRoot, "minos-concurrent");
        ProjectIndexingService service = ProjectIndexingServices.withLockManager(
                projects,
                new SqliteIndexRepository(database),
                new ProjectScanner(),
                List.of(analyzer),
                new LuceneSearchIndex(paths),
                List.of(),
                List.of(),
                null,
                Duration.ofSeconds(5),
                ProjectIndexLockManager.fileBacked(paths));
        return new Fixture(paths, project, service);
    }

    private static String payload(ProjectDescriptor project) throws IOException {
        ObjectNode document = JSON.createObjectNode();
        ObjectNode symbol = document.putArray("symbols").addObject();
        symbol.put("filePath", "App.java");
        symbol.put("kind", "CLASS");
        symbol.put("name", "App");
        symbol.put("qualifiedName", "App");
        symbol.put("signature", "App");
        symbol.put("startLine", 1);
        symbol.put("endLine", 1);
        symbol.put("resolutionStatus", "RESOLVED");
        document.putArray("relations");
        document.put("producer", "MINOS");
        document.putObject("project").put("rootPath", project.rootPath().toRealPath().toString());
        document.put("contractVersion", "1");
        return JSON.writeValueAsString(document);
    }

    private record Fixture(NexusPaths paths, ProjectDescriptor project, ProjectIndexingService service) {
    }

    /** Bloque l'indexation au milieu de l'analyse du premier fichier, thread d'indexation compris. */
    private static final class BlockingAnalyzer implements LanguageAnalyzer {
        private final CountDownLatch analysing;
        private final CountDownLatch release;

        private BlockingAnalyzer(CountDownLatch analysing, CountDownLatch release) {
            this.analysing = analysing;
            this.release = release;
        }

        @Override
        public boolean supports(Path file) {
            return file.toString().endsWith(".java");
        }

        @Override
        public AnalysisResult analyze(Path projectRoot, Path file) throws IOException {
            analysing.countDown();
            try {
                if (!release.await(SAFETY_CAP.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IOException("Analyse jamais relâchée par le test");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Analyse interrompue", interrupted);
            }
            return new AnalysisResult(file, "java", List.of(), List.of());
        }
    }
}
