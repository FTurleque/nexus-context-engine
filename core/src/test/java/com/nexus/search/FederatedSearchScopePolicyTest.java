package com.nexus.search;

import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.DeterministicContextRanker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FederatedSearchScopePolicyTest {

    @TempDir
    Path root;

    @Test
    void acceptsOneAndExactlyOneHundredProjects() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FederatedSearchService service = service(calls);

        service.search(projects(1), "query", 10, false);
        assertEquals(1, calls.get());

        calls.set(0);
        service.search(projects(100), "query", 10, false);
        assertEquals(100, calls.get());
    }

    @Test
    void acceptsDuplicateHeavyScopeWhenUniqueCountIsWithinLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FederatedSearchService service = service(calls);
        ProjectDescriptor project = project(1);
        List<ProjectDescriptor> duplicates = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            duplicates.add(project);
        }

        service.search(duplicates, "query", 10, false);

        assertEquals(1, calls.get());
    }

    @Test
    void rejectsOneHundredAndOneBeforeAnyLocalSearch() {
        AtomicInteger calls = new AtomicInteger();
        FederatedSearchService service = service(calls);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.search(projects(101), "query", 10, false));

        assertEquals(FederatedScopePolicy.TOO_MANY_PROJECTS_MESSAGE, exception.getMessage());
        assertEquals(0, calls.get());
    }

    private FederatedSearchService service(AtomicInteger calls) {
        SearchStrategy countingStrategy = (project, query, limit) -> {
            calls.incrementAndGet();
            return List.of();
        };
        return new FederatedSearchService(new SearchService(
                List.of(countingStrategy),
                List.of(),
                new DeterministicContextRanker()));
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
