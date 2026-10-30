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
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexingServiceTimeoutTest {

    /** Borne de sûreté d'un importer jamais relâché ; aucune assertion ne porte sur cette durée. */
    private static final Duration WORKER_SAFETY_CAP = Duration.ofSeconds(30);

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundsCodeIndexImportersAndMarksTheProjectFailed() throws IOException, SQLException, InterruptedException {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(projectRoot.resolve("App.java"), "class App {}\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        var project = new ProjectRegistry(projectRepository).register(projectRoot, "timeout-demo");

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch importerFinished = new CountDownLatch(1);
        CodeIndexImporter stubbornImporter = new CodeIndexImporter() {
            @Override
            public String sourceProvider() {
                return "stubborn-importer";
            }

            @Override
            public Optional<CodeIntelligenceSnapshot> importIndex(Path root) {
                NonCooperativeTaskSupport.ignoreInterruptsUntil(release, WORKER_SAFETY_CAP);
                importerFinished.countDown();
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

        try {
            IOException failure = assertThrows(IOException.class, () -> service.index(project.id()));

            assertTrue(failure.getMessage().contains("stubborn-importer"), failure.getMessage());
            assertTrue(failure.getMessage().contains("timeout global de 50 ms"), failure.getMessage());
            assertEquals(1L, importerFinished.getCount(),
                    "L'indexation ne doit pas attendre l'importer récalcitrant, encore bloqué");
            assertEquals(IndexStatus.FAILED, projectRepository.findById(project.id()).orElseThrow().indexStatus());
        } finally {
            release.countDown();
        }
        assertTrue(importerFinished.await(WORKER_SAFETY_CAP.toSeconds(), TimeUnit.SECONDS));
    }
}
