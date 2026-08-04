package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.ScannedFile;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteTargetedFileLookupTest {

    private static final Instant MODIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void targetedLookupBindsPathsAsDataAndKeepsProjectsIsolated() throws Exception {
        NexusPaths paths = new NexusPaths(tempDir.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        SqliteIndexRepository index = new SqliteIndexRepository(database);

        UUID firstProject = registerProject(projects, "first");
        UUID secondProject = registerProject(projects, "second");
        String injectionLikePath = "src/a') OR 1=1 --.java";
        String jsonSensitivePath = "src/quoted-\"name.java";

        index.applyChanges(firstProject, List.of(
                update("src/alpha.java"),
                update(injectionLikePath),
                update(jsonSensitivePath),
                update("src/zeta.java")), Set.of());
        index.applyChanges(secondProject, List.of(update("src/alpha.java")), Set.of());

        Set<String> requestedPaths = new LinkedHashSet<>();
        for (int indexValue = 0; indexValue < 1_100; indexValue++) {
            requestedPaths.add("missing/" + indexValue + ".java");
        }
        requestedPaths.add(injectionLikePath);
        requestedPaths.add(jsonSensitivePath);
        requestedPaths.add("src/alpha.java");

        Map<String, IndexedFile> selected = index.findFiles(firstProject, requestedPaths);

        assertEquals(Set.of("src/alpha.java", injectionLikePath, jsonSensitivePath), selected.keySet());
        assertEquals(Set.of(firstProject), selected.values().stream()
                .map(IndexedFile::projectId)
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, index.findFiles(firstProject).size());
        assertEquals(Set.of("src/alpha.java"), index.findFiles(secondProject, requestedPaths).keySet());
        assertEquals(Map.of(), index.findFiles(firstProject, Set.of()));
    }

    private UUID registerProject(SqliteProjectRepository projects, String name) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(name));
        UUID projectId = UUID.randomUUID();
        projects.save(new ProjectDescriptor(
                projectId,
                name,
                root.toRealPath(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED));
        return projectId;
    }

    private IndexedFileUpdate update(String relativePath) {
        Path absolutePath = tempDir.resolve("files").resolve(Integer.toHexString(relativePath.hashCode()) + ".java");
        ScannedFile file = new ScannedFile(
                absolutePath,
                relativePath,
                "java",
                1L,
                "hash-" + relativePath,
                MODIFIED_AT,
                1,
                FileCategory.SOURCE);
        return new IndexedFileUpdate(
                file,
                new AnalysisResult(absolutePath, "java", List.of(), List.of()));
    }
}
