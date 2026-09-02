package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectIndexMutationDuringIndexingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void neverPublishesReadyFromMixedRepositoryVersions() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App { void before() {} }\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projects = new SqliteProjectRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projects).register(projectRoot, "mutation");

        LanguageAnalyzer mutatingAnalyzer = new LanguageAnalyzer() {
            @Override
            public boolean supports(Path file) {
                return file.toString().endsWith(".java");
            }

            @Override
            public AnalysisResult analyze(Path root, Path file) {
                throw new AssertionError("The indexing pipeline must provide the immutable content snapshot");
            }

            @Override
            public AnalysisResult analyze(Path root, Path file, String content) throws IOException {
                Files.writeString(file, "class App { void after() {} }\n");
                return new AnalysisResult(file, "java", List.of(), List.of());
            }
        };

        ProjectIndexingService service = new ProjectIndexingService(
                projects,
                new SqliteIndexRepository(database),
                new ProjectScanner(),
                List.of(mutatingAnalyzer),
                new LuceneSearchIndex(paths));

        assertThrows(IOException.class, () -> service.index(project.id()));
        assertEquals(IndexStatus.FAILED, projects.findById(project.id()).orElseThrow().indexStatus());
    }
}
