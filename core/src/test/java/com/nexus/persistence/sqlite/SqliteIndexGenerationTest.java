package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteIndexGenerationTest {

    @TempDir
    Path tempDir;

    @Test
    void generationPersistsAndNoOpExternalSnapshotsDoNotInvalidateCaches() throws Exception {
        NexusPaths paths = new NexusPaths(tempDir.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        SqliteProjectRepository projects = new SqliteProjectRepository(database);
        SqliteIndexRepository index = new SqliteIndexRepository(database);

        UUID projectId = UUID.randomUUID();
        Path projectRoot = tempDir.resolve("project").toAbsolutePath().normalize();
        projects.save(new ProjectDescriptor(
                projectId,
                "generation-test",
                projectRoot,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED));

        assertEquals(0L, index.generation(projectId));

        // An empty external snapshot against an already-empty provider is a true no-op.
        index.replaceExternalCodeIntelligence(projectId, CodeIntelligenceSnapshot.empty("scip"));
        assertEquals(0L, index.generation(projectId));

        ScannedFile file = new ScannedFile(
                projectRoot.resolve("src/Main.java"),
                "src/Main.java",
                "java",
                12,
                "abc",
                Instant.parse("2026-08-08T00:00:00Z"),
                3,
                FileCategory.SOURCE);
        index.applyChanges(
                projectId,
                List.of(new IndexedFileUpdate(
                        file,
                        new AnalysisResult(file.absolutePath(), "java", List.of(), List.of()))),
                Set.of());
        assertEquals(1L, index.generation(projectId));

        SqliteIndexRepository reopened = new SqliteIndexRepository(new SqliteDatabase(paths));
        assertEquals(1L, reopened.generation(projectId));

        reopened.replaceExternalCodeIntelligence(projectId, CodeIntelligenceSnapshot.empty("minos"));
        assertEquals(1L, reopened.generation(projectId));

        CodeIntelligenceSnapshot populated = new CodeIntelligenceSnapshot(
                "scip",
                List.of(new IndexedSymbol(
                        "src/Main.java",
                        new CodeSymbol(
                                SymbolKind.TYPE,
                                "Main",
                                "scip:demo/Main#",
                                "Main",
                                1,
                                1,
                                "scip"))),
                List.of(new IndexedRelation(
                        "src/Main.java",
                        new SymbolRelation(
                                RelationKind.REFERENCES,
                                "scip:demo/Main#",
                                "scip:demo/Dependency#",
                                1.0d,
                                "scip"))));

        reopened.replaceExternalCodeIntelligence(projectId, populated);
        assertEquals(2L, reopened.generation(projectId));

        // Importing the exact same non-empty provider state again must not invalidate caches.
        reopened.replaceExternalCodeIntelligence(projectId, populated);
        assertEquals(2L, reopened.generation(projectId));
    }
}
