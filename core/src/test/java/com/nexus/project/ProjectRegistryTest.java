package com.nexus.project;

import com.nexus.config.NexusPaths;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRegistryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void registrationIsIdempotentAndSurvivesRepositoryReopening() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("demo-project"));
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));

        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRegistry registry = new ProjectRegistry(new SqliteProjectRepository(database));

        ProjectDescriptor first = registry.register(projectRoot, "demo");
        ProjectDescriptor second = registry.register(projectRoot.resolve("."), "autre-nom");

        assertEquals(first.id(), second.id());
        assertEquals("demo", second.name());
        assertEquals(1, registry.list().size());

        ProjectRegistry reopenedRegistry = new ProjectRegistry(
                new SqliteProjectRepository(new SqliteDatabase(paths)));
        ProjectDescriptor reloaded = reopenedRegistry.get(first.id());

        assertEquals(first.id(), reloaded.id());
        assertEquals(first.rootPath(), reloaded.rootPath());
        assertEquals(IndexStatus.NOT_INDEXED, reloaded.indexStatus());
    }

    @Test
    void concurrentRegistrationOfTheSameCanonicalRootIsIdempotent() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("concurrent-project"));
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("concurrent-home"));
        ProjectRegistry firstRegistry = new ProjectRegistry(
                new SqliteProjectRepository(new SqliteDatabase(paths)));
        ProjectRegistry secondRegistry = new ProjectRegistry(
                new SqliteProjectRepository(new SqliteDatabase(paths)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProjectDescriptor> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return firstRegistry.register(projectRoot, "first");
            });
            Future<ProjectDescriptor> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return secondRegistry.register(projectRoot.resolve("."), "second");
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            ProjectDescriptor firstResult = first.get(15, TimeUnit.SECONDS);
            ProjectDescriptor secondResult = second.get(15, TimeUnit.SECONDS);

            assertEquals(firstResult.id(), secondResult.id());
            assertEquals(firstResult.rootPath(), secondResult.rootPath());
            assertEquals(firstResult.name(), secondResult.name());
            assertTrue(Set.of("first", "second").contains(firstResult.name()));
            assertEquals(1, firstRegistry.list().size());
        }
    }
}
