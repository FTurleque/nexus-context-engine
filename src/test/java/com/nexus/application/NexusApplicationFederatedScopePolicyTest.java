package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NexusApplicationFederatedScopePolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void applicationExposesSameHundredProjectContractForSearchAndContext() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        NexusApplication application = NexusApplication.create(paths);
        SqliteProjectRepository repository = new SqliteProjectRepository(new SqliteDatabase(paths));
        List<UUID> ids = new ArrayList<>();
        for (int index = 1; index <= 101; index++) {
            UUID id = new UUID(0L, index);
            ids.add(id);
            repository.save(new ProjectDescriptor(
                    id,
                    "project-" + index,
                    temporaryDirectory.resolve("project-" + index),
                    ProjectSourceType.LOCAL,
                    Set.of("java"),
                    Set.of(),
                    null,
                    IndexStatus.READY));
        }

        IllegalArgumentException search = assertThrows(
                IllegalArgumentException.class,
                () -> application.searchAcrossProjects(ids, "query", 10, false));
        IllegalArgumentException context = assertThrows(
                IllegalArgumentException.class,
                () -> application.contextAcrossProjects(ids, "task", 1_000, Set.of(), Map.of(), false));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, search.getMessage());
        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, context.getMessage());
    }

    @Test
    void tooManyDistinctUuidsWinsBeforeAnyMissingProjectLookup() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("empty-nexus-home")));
        List<UUID> nonexistentIds = new ArrayList<>();
        for (int index = 1; index <= FederatedScopePolicy.MAX_PROJECTS + 1; index++) {
            nonexistentIds.add(new UUID(1L, index));
        }

        IllegalArgumentException search = assertThrows(
                IllegalArgumentException.class,
                () -> application.searchAcrossProjects(nonexistentIds, "query", 10, false));
        IllegalArgumentException context = assertThrows(
                IllegalArgumentException.class,
                () -> application.contextAcrossProjects(
                        nonexistentIds, "task", 1_000, Set.of(), Map.of(), false));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, search.getMessage());
        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, context.getMessage());
    }
}
