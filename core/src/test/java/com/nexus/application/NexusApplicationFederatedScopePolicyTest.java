package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
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
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void federatedReadsReleaseLocksAfterSuccessAndPartialAcquisitionFailure() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("federated-locks"));
        try (NexusApplication application = NexusApplication.create(paths)) {
            SqliteProjectRepository repository = new SqliteProjectRepository(new SqliteDatabase(paths));
            List<UUID> ids = new ArrayList<>();
            for (int index = 1; index <= 25; index++) {
                UUID id = new UUID(0L, index);
                ids.add(id);
                repository.save(new ProjectDescriptor(id, "project-" + index,
                        temporaryDirectory.resolve("project-" + index), ProjectSourceType.LOCAL,
                        Set.of(), Set.of(), null, IndexStatus.READY));
            }
            assertEquals(25, application.searchAcrossProjects(ids, "absent", 10, false).projects().size());
            UUID blockedId = ids.getLast();
            paths.ensurePrivateFile(paths.projectIndexLock(blockedId));
            try (FileChannel channel = FileChannel.open(paths.projectIndexLock(blockedId), StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                assertThrows(IllegalStateException.class,
                        () -> application.searchAcrossProjects(ids, "absent", 10, false));
                assertThrows(IllegalStateException.class,
                        () -> application.contextAcrossProjects(ids, "absent", 1_000, Set.of(), Map.of(), false));
            }
            ProjectIndexLockManager manager = ProjectIndexLockManager.fileBacked(paths);
            for (UUID id : ids) {
                try (ProjectIndexLockManager.LockHandle ignored = manager.acquire(id)) {
                    // Chaque projet reste inscriptible après succès et après échec partiel.
                }
            }
        }
    }

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
