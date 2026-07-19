package io.github.fturleque.nexus.index;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.index.java.JavaParserLanguageAnalyzer;
import io.github.fturleque.nexus.index.scan.ProjectScanner;
import io.github.fturleque.nexus.persistence.sqlite.SqliteDatabase;
import io.github.fturleque.nexus.persistence.sqlite.SqliteIndexRepository;
import io.github.fturleque.nexus.persistence.sqlite.SqliteProjectRepository;
import io.github.fturleque.nexus.project.IndexStatus;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRegistry;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.search.lucene.LuceneSearchIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexingServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesIncrementallyAndPropagatesChangesAndDeletions() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path sourceFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;
                import java.util.List;
                class App {
                    void run() {}
                }
                """);

        Path generatedFile = projectRoot.resolve("target/generated/Generated.java");
        Files.createDirectories(generatedFile.getParent());
        Files.writeString(generatedFile, "class Generated {}\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry registry = new ProjectRegistry(projectRepository);
        ProjectDescriptor project = registry.register(projectRoot, "demo");

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths));

        IndexingReport first = service.index(project.id());
        assertEquals(1, first.scannedFiles());
        assertEquals(1, first.changedFiles());
        assertEquals(0, first.removedFiles());
        assertTrue(first.fullSearchRebuild());
        assertEquals(new IndexStatistics(1, 2, 1), first.statistics());
        assertEquals(1, luceneDocumentCount(paths, project));
        assertEquals(IndexStatus.READY, registry.get(project.id()).indexStatus());

        IndexingReport second = service.index(project.id());
        assertEquals(0, second.changedFiles());
        assertEquals(0, second.removedFiles());
        assertFalse(second.fullSearchRebuild());
        assertEquals(new IndexStatistics(1, 2, 1), second.statistics());

        Files.writeString(sourceFile, """
                package demo;
                import java.util.List;
                class App {
                    void run() {}
                    void stop() {}
                }
                """);

        IndexingReport third = service.index(project.id());
        assertEquals(1, third.changedFiles());
        assertEquals(new IndexStatistics(1, 3, 1), third.statistics());
        assertEquals(1, luceneDocumentCount(paths, project));

        Files.delete(sourceFile);
        IndexingReport fourth = service.index(project.id());
        assertEquals(0, fourth.scannedFiles());
        assertEquals(0, fourth.changedFiles());
        assertEquals(1, fourth.removedFiles());
        assertEquals(new IndexStatistics(0, 0, 0), fourth.statistics());
        assertEquals(0, luceneDocumentCount(paths, project));
    }

    private static int luceneDocumentCount(NexusPaths paths, ProjectDescriptor project) throws Exception {
        try (Directory directory = FSDirectory.open(paths.projectLuceneIndex(project.id()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }
}
