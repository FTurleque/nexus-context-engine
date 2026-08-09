package com.nexus.context;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FederatedContextScopePolicyTest {

    @TempDir
    Path root;

    @Test
    void acceptsOneAndExactlyOneHundredProjects() {
        AtomicInteger calls = new AtomicInteger();
        FederatedContextService service = service(calls);

        service.build(projects(1), "task", 100, Set.of(), Map.of(), false);
        assertEquals(1, calls.get());

        calls.set(0);
        service.build(projects(100), "task", 1_000, Set.of(), Map.of(), false);
        assertEquals(100, calls.get());
    }

    @Test
    void acceptsDuplicateHeavyScopeWhenUniqueCountIsWithinLimit() {
        AtomicInteger calls = new AtomicInteger();
        FederatedContextService service = service(calls);
        ProjectDescriptor project = project(1);
        List<ProjectDescriptor> duplicates = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            duplicates.add(project);
        }

        service.build(duplicates, "task", 100, Set.of(), Map.of(), false);

        assertEquals(1, calls.get());
    }

    @Test
    void rejectsOneHundredAndOneBeforeAnyLocalContextBuild() {
        AtomicInteger calls = new AtomicInteger();
        FederatedContextService service = service(calls);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.build(projects(101), "task", 1_000, Set.of(), Map.of(), false));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, exception.getMessage());
        assertEquals(0, calls.get());
    }

    private FederatedContextService service(AtomicInteger calls) {
        ContextBuilder builder = request -> {
            calls.incrementAndGet();
            return new ContextBundle(List.of(), request.tokenBudget(), 0, List.of(), Map.of());
        };
        return new FederatedContextService(builder);
    }

    private List<ProjectDescriptor> projects(int count) {
        List<ProjectDescriptor> projects = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            projects.add(project(index + 1));
        }
        return List.copyOf(projects);
    }

    private ProjectDescriptor project(int index) {
        return new ProjectDescriptor(
                new UUID(0L, index),
                "p" + index,
                root.resolve("p" + index),
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }
}
