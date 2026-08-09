package com.nexus.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NexusMcpFederatedScopePolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mcpFederatedResolutionDelegatesToTheCommonSearchAndContextLimit() throws Exception {
        Fixture fixture = fixture(101);
        NexusMcpTools tools = new NexusMcpTools(fixture.application(), new ObjectMapper());
        Method resolveProjects = NexusMcpTools.class.getDeclaredMethod("resolveProjects", Map.class);
        resolveProjects.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ProjectDescriptor> resolved = (List<ProjectDescriptor>) resolveProjects.invoke(
                tools,
                Map.of("projects", fixture.ids().stream().map(UUID::toString).toList()));
        List<UUID> ids = resolved.stream().map(ProjectDescriptor::id).toList();

        IllegalArgumentException search = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.application().searchAcrossProjects(ids, "query", 10, false));
        IllegalArgumentException context = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.application().contextAcrossProjects(ids, "task", 1_000, Set.of(), Map.of(), false));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, search.getMessage());
        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, context.getMessage());
    }

    private Fixture fixture(int count) throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        NexusApplication application = NexusApplication.create(paths);
        SqliteProjectRepository repository = new SqliteProjectRepository(new SqliteDatabase(paths));
        List<UUID> ids = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
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
        return new Fixture(application, List.copyOf(ids));
    }

    private record Fixture(NexusApplication application, List<UUID> ids) {
    }
}
