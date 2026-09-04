package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexingServiceTimeoutTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundsCodeIndexImportersAndMarksTheProjectFailed() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(projectRoot.resolve("App.java"), "class App {}\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        var project = new ProjectRegistry(projectRepository).register(projectRoot, "timeout-demo");

        CodeIndexImporter stubbornImporter = new CodeIndexImporter() {
            @Override
            public String sourceProvider() {
                return "stubborn-importer";
            }

            @Override
            public Optional<CodeIntelligenceSnapshot> importIndex(Path root) {
                long stopAt = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < stopAt) {
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ignored) {
                        // Simule une intégration qui ignore l'interruption.
                    }
                }
                return Optional.empty();
            }
        };

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths),
                List.of(stubbornImporter),
                List.of(),
                null,
                Duration.ofMillis(50));

        long startedAt = System.nanoTime();
        IOException failure = assertThrows(IOException.class, () -> service.index(project.id()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(failure.getMessage().contains("stubborn-importer"));
        assertTrue(failure.getMessage().contains("timeout global"));
        assertTrue(elapsedMillis < 1_500L,
                () -> "L'indexation ne doit pas attendre l'importer récalcitrant : " + elapsedMillis + " ms");
        assertEquals(IndexStatus.FAILED, projectRepository.findById(project.id()).orElseThrow().indexStatus());
    }
}
