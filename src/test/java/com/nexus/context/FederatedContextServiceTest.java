package com.nexus.context;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
