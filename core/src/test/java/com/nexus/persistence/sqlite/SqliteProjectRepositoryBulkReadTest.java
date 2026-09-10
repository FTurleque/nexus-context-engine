package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
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

class SqliteProjectRepositoryBulkReadTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void findAllHydratesLanguagesAndTechnologiesForEveryProject() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        SqliteProjectRepository repository = new SqliteProjectRepository(database);

        ProjectDescriptor beta = project(
                "beta",
                Set.of("java", "sql"),
                Set.of("maven", "sqlite"),
                IndexStatus.READY);
        ProjectDescriptor alpha = project(
                "alpha",
                Set.of("python"),
                Set.of("pytest"),
                IndexStatus.NOT_INDEXED);
        ProjectDescriptor gamma = project(
                "gamma",
                Set.of(),
                Set.of(),
                IndexStatus.FAILED);

        repository.save(beta);
        repository.save(alpha);
        repository.save(gamma);

        List<ProjectDescriptor> projects = repository.findAll();

        assertEquals(List.of("alpha", "beta", "gamma"), projects.stream().map(ProjectDescriptor::name).toList());
        assertEquals(alpha, projects.get(0));
        assertEquals(beta, projects.get(1));
        assertEquals(gamma, projects.get(2));
    }

    private ProjectDescriptor project(
            String name,
            Set<String> languages,
            Set<String> technologies,
            IndexStatus status) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                temporaryDirectory.resolve(name).toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL,
                languages,
                technologies,
                status == IndexStatus.READY ? Instant.parse("2026-09-10T00:00:00Z") : null,
                status);
    }
}
