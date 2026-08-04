package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteIndexGenerationTest {

    @TempDir
    Path tempDir;

    @Test
    void generationIsMonotoneAndPersistsAcrossDatabaseReopen() throws Exception {
        NexusPaths paths = new NexusPaths(tempDir.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        SqliteIndexRepository index = new SqliteIndexRepository(database);

        UUID projectId = UUID.randomUUID();
        projects.save(new ProjectDescriptor(
                projectId,
                "generation-test",
                tempDir.resolve("project").toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED));

        assertEquals(0L, index.generation(projectId));

        index.replaceExternalCodeIntelligence(projectId, CodeIntelligenceSnapshot.empty("scip"));
        assertEquals(1L, index.generation(projectId));

        SqliteIndexRepository reopened = new SqliteIndexRepository(new SqliteDatabase(paths));
        assertEquals(1L, reopened.generation(projectId));

        reopened.replaceExternalCodeIntelligence(projectId, CodeIntelligenceSnapshot.empty("minos"));
        assertEquals(2L, reopened.generation(projectId));
    }
}
