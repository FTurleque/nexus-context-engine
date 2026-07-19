package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.SearchDocument;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

    @Test
    void keepsSkillsCanonicalInSqliteButPurgesThemFromGenericLuceneIndex() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("skills-project"));
        Path sourceFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo; class App {}\n");
        Path skillFile = projectRoot.resolve(".agents/skills/testing/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: testing
                description: Run focused tests for Java changes.
                ---
                # Testing skill
                """);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("skills-nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "skills-demo");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer(), new MarkdownLanguageAnalyzer()),
                searchIndex);

        IndexingReport first = service.index(project.id());
        assertEquals(2, first.statistics().files());
        assertEquals(1, luceneDocumentCount(paths, project));

        // Simule un document SKILL laissé dans Lucene par une ancienne version.
        searchIndex.applyChanges(
                project.id(),
                List.of(new SearchDocument(
                        ".agents/skills/testing/SKILL.md",
                        "markdown",
                        FileCategory.SKILL,
                        "legacy skill document",
                        List.of())),
                Set.of());
        assertEquals(2, luceneDocumentCount(paths, project));

        IndexingReport second = service.index(project.id());
        assertEquals(0, second.changedFiles());
        assertEquals(0, second.removedFiles());
        assertEquals(2, second.statistics().files());
        assertEquals(1, luceneDocumentCount(paths, project));
    }

    private static int luceneDocumentCount(NexusPaths paths, ProjectDescriptor project) throws Exception {
        try (Directory directory = FSDirectory.open(paths.projectLuceneIndex(project.id()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }
}
