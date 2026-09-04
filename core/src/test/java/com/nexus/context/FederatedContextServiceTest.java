package com.nexus.context;

import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedContextServiceTest {

    @TempDir
    Path root;

    @Test
    void enforcesGlobalBudgetProjectProvenanceAndCrossProjectDeduplication() {
        ProjectDescriptor first = project("first", root.resolve("first"));
        ProjectDescriptor second = project("second", root.resolve("second"));

        ContextBuilder builder = request -> {
            ProjectDescriptor project = request.projectId().equals(first.id()) ? first : second;
            ContextItem shared = item(project.rootPath().resolve("AGENTS.md"), "shared instructions", 60);
            ContextItem unique = item(project.rootPath().resolve("src/Main.java"), project.name(), 40);
            return new ContextBundle(
                    List.of(shared, unique),
                    request.tokenBudget(),
                    100,
                    List.of(),
                    Map.of("project", project.name()));
        };

        FederatedContextBundle bundle = new FederatedContextService(builder).build(
                List.of(first, second),
                "task",
                200,
                Set.of(),
                Map.of(),
                true);

        assertEquals(3, bundle.items().size());
        assertEquals(140, bundle.estimatedTokens());
        assertTrue(bundle.estimatedTokens() <= bundle.tokenBudget());
        assertEquals(1, bundle.metadata().get("crossProjectDeduplicatedItems"));
        assertEquals(0, bundle.metadata().get("starvedProjectCount"));
        assertEquals(
                Set.of(first.id(), second.id()),
                bundle.items().stream().map(item -> item.project().id()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void refillsBudgetReleasedByASparseProject() {
        ProjectDescriptor sparse = project("sparse", root.resolve("sparse"));
        ProjectDescriptor rich = project("rich", root.resolve("rich"));

        ContextBuilder builder = request -> {
            if (request.projectId().equals(sparse.id())) {
                return withinBudget(
                        request.tokenBudget(),
                        List.of(item(sparse.rootPath().resolve("README.md"), "small", 20)));
            }
            return withinBudget(
                    request.tokenBudget(),
                    List.of(
                            item(rich.rootPath().resolve("A.java"), "A", 60),
                            item(rich.rootPath().resolve("B.java"), "B", 40),
                            item(rich.rootPath().resolve("C.java"), "C", 50)));
        };

        FederatedContextBundle bundle = new FederatedContextService(builder).build(
                List.of(sparse, rich),
                "task",
                200,
                Set.of(),
                Map.of(),
                true);

        assertEquals(170, bundle.estimatedTokens());
        assertEquals(4, bundle.items().size());
        assertEquals(50, bundle.metadata().get("refillTokens"));
        assertEquals(1, bundle.metadata().get("refillItems"));
        assertEquals(30, bundle.metadata().get("unusedTokens"));
        assertEquals("fair-floor-bounded-overfetch-global-refill", bundle.metadata().get("mergePolicy"));
    }

    @Test
    void doesNotLetASmallerLowerRankedItemLeapfrogADeferredCandidate() {
        ProjectDescriptor sparse = project("sparse-order", root.resolve("sparse-order"));
        ProjectDescriptor rich = project("rich-order", root.resolve("rich-order"));

        ContextBuilder builder = request -> {
            if (request.projectId().equals(sparse.id())) {
                return withinBudget(
                        request.tokenBudget(),
                        List.of(item(sparse.rootPath().resolve("README.md"), "sparse", 20)));
            }
            return withinBudget(
                    request.tokenBudget(),
                    List.of(
                            item(rich.rootPath().resolve("High.java"), "high-ranked", 120),
                            item(rich.rootPath().resolve("Low.java"), "lower-ranked", 30)));
        };

        FederatedContextBundle bundle = new FederatedContextService(builder).build(
                List.of(sparse, rich),
                "task",
                200,
                Set.of(),
                Map.of(),
                true);

        assertEquals(3, bundle.items().size());
        assertEquals("sparse", bundle.items().get(0).item().content());
        assertEquals("high-ranked", bundle.items().get(1).item().content());
        assertEquals("lower-ranked", bundle.items().get(2).item().content());
        assertEquals(150, bundle.metadata().get("refillTokens"));
        assertEquals(2, bundle.metadata().get("refillItems"));
    }

    @Test
    void boundsLocalCandidateBudgetsAndRejectsOversizedProjectScopes() {
        List<ProjectDescriptor> projects = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            projects.add(project("p" + index, root.resolve("p" + index)));
        }
        AtomicInteger candidateBudgetTotal = new AtomicInteger();
        ContextBuilder builder = request -> {
            candidateBudgetTotal.addAndGet(request.tokenBudget());
            return new ContextBundle(List.of(), request.tokenBudget(), 0, List.of(), Map.of());
        };

        FederatedContextBundle bundle = new FederatedContextService(builder).build(
                projects, "task", 1_000, Set.of(), Map.of(), false);

        assertEquals(3_000, candidateBudgetTotal.get());
        assertEquals(3_000, bundle.metadata().get("candidateBudgetTotal"));

        List<ProjectDescriptor> tooMany = new ArrayList<>();
        for (int index = 0; index <= FederatedScopePolicy.MAX_PROJECTS; index++) {
            tooMany.add(project("too-many-" + index, root.resolve("too-many-" + index)));
        }
        assertThrows(IllegalArgumentException.class, () ->
                new FederatedContextService(builder).build(
                        tooMany, "task", 1_000, Set.of(), Map.of(), false));
    }

    private static ContextBundle withinBudget(int tokenBudget, List<ContextItem> candidates) {
        List<ContextItem> selected = new ArrayList<>();
        int tokens = 0;
        for (ContextItem candidate : candidates) {
            if (tokens + candidate.estimatedTokens() <= tokenBudget) {
                selected.add(candidate);
                tokens += candidate.estimatedTokens();
            }
        }
        return new ContextBundle(selected, tokenBudget, tokens, List.of(), Map.of());
    }

    private static ProjectDescriptor project(String name, Path path) {
        return new ProjectDescriptor(
                UUID.randomUUID(), name, path, ProjectSourceType.LOCAL,
                Set.of("java"), Set.of(), null, IndexStatus.READY);
    }

    private static ContextItem item(Path path, String content, int tokens) {
        return new ContextItem(
                CandidateType.FILE,
                path,
                null,
                1,
                1,
                content,
                1.0d,
                Map.of("test", 1.0d),
                List.of("test"),
                tokens,
                false);
    }
}
