package io.github.fturleque.nexus.project;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.persistence.sqlite.SqliteDatabase;
import io.github.fturleque.nexus.persistence.sqlite.SqliteProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(projectRoot.toAbsolutePath().normalize(), reloaded.rootPath());
        assertEquals(IndexStatus.NOT_INDEXED, reloaded.indexStatus());
    }
}
