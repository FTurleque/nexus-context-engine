package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
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

class ProjectIndexingCorpusLimitTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void corpusOverflowMarksProjectFailedWithoutPublishingPartialReadyState() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("project/src"));
        Files.writeString(root.resolve("A.java"), "class A {}\n");
        Path projectRoot = root.getParent();

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projects = new SqliteProjectRepository(database);
        IndexRepository index = new SqliteIndexRepository(database);
        ProjectRegistry registry = new ProjectRegistry(projects);
        ProjectDescriptor project = registry.register(projectRoot, "corpus-budget");

        ProjectIndexingService service = new ProjectIndexingService(
                projects,
                index,
                new ProjectScanner(1024L, 2, 1024L),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths));

        service.index(project.id());
        assertEquals(IndexStatus.READY, registry.get(project.id()).indexStatus());
        assertEquals(1, index.findFiles(project.id()).size());

        Files.writeString(root.resolve("B.java"), "class B {}\n");
        assertThrows(IOException.class, () -> service.index(project.id()));

        assertEquals(IndexStatus.FAILED, registry.get(project.id()).indexStatus());
        assertEquals(1, index.findFiles(project.id()).size(),
                "l'ancien index complet peut rester physiquement présent, mais ne doit jamais être republié READY");
    }
}
