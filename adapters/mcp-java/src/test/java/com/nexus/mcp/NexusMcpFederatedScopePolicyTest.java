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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NexusMcpFederatedScopePolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mcpRejectsOneHundredAndOneExplicitUuidsBeforeAnyProjectResolution() throws Exception {
        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("empty-nexus-home")));
        NexusMcpTools tools = new NexusMcpTools(application, new ObjectMapper());
        Method resolveProjects = resolveProjectsMethod();

        List<String> selectors = new ArrayList<>();
        for (int index = 1; index <= FederatedScopePolicy.MAX_PROJECTS + 1; index++) {
            selectors.add(new UUID(7L, index).toString());
        }

        InvocationTargetException invocation = assertThrows(
                InvocationTargetException.class,
                () -> resolveProjects.invoke(tools, Map.of("projects", selectors)));
        IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, invocation.getCause());

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, failure.getMessage());
    }

    @Test
    void mcpKeepsUuidDeduplicationWhenSelectorsAliasTheSameProject() throws Exception {
        Fixture fixture = fixture(1);
        NexusMcpTools tools = new NexusMcpTools(fixture.application(), new ObjectMapper());
        Method resolveProjects = resolveProjectsMethod();
        UUID id = fixture.ids().getFirst();

        @SuppressWarnings("unchecked")
        List<ProjectDescriptor> resolved = (List<ProjectDescriptor>) resolveProjects.invoke(
                tools,
                Map.of("projects", List.of(
                        id.toString(),
                        id.toString(),
                        "project-1",
                        "PROJECT-1")));

        assertEquals(1, resolved.size());
        assertEquals(id, resolved.getFirst().id());
    }

    private static Method resolveProjectsMethod() throws NoSuchMethodException {
        Method method = NexusMcpTools.class.getDeclaredMethod("resolveProjects", Map.class);
        method.setAccessible(true);
        return method;
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
