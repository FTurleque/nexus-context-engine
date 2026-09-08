package com.nexus.context;

import com.nexus.context.source.ContextDiscoveryBudget;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedContextDiscoveryBudgetTest {

    @TempDir
    Path root;

    @Test
    void sharesOneNativeDiscoveryBudgetAcrossEveryFederatedProject() {
        ProjectDescriptor first = project("first", root.resolve("first"));
        ProjectDescriptor second = project("second", root.resolve("second"));
        List<ContextDiscoveryBudget> observedBudgets = new ArrayList<>();

        ContextBuilder builder = new ContextBuilder() {
            @Override
            public ContextBundle build(ContextRequest request) {
                throw new AssertionError("Federated context must use caller-owned budgets");
            }

            @Override
            public ContextBundle build(
                    ContextRequest request,
                    ContextMaterializationBudget materializationBudget,
                    ContextDiscoveryBudget discoveryBudget) {
                observedBudgets.add(discoveryBudget);
                return new ContextBundle(List.of(), request.tokenBudget(), 0, List.of(), Map.of());
            }
        };

        FederatedContextBundle bundle = new FederatedContextService(builder).build(
                List.of(first, second), "task", 200, Set.of(), Map.of(), false);

        assertEquals(2, observedBudgets.size());
        assertSame(observedBudgets.get(0), observedBudgets.get(1));
        assertEquals(observedBudgets.get(0).limits(), bundle.metadata().get("nativeDiscoveryLimits"));

        ContextDiscoveryBudget.Snapshot recorded =
                (ContextDiscoveryBudget.Snapshot) bundle.metadata().get("nativeDiscoveryWork");
        ContextDiscoveryBudget.Snapshot current = observedBudgets.get(0).snapshot();
        assertEquals(recorded.visitedEntries(), current.visitedEntries());
        assertEquals(recorded.candidateResources(), current.candidateResources());
        assertEquals(recorded.cumulativeBytes(), current.cumulativeBytes());
        assertTrue(current.elapsedMillis() >= recorded.elapsedMillis());
    }

    private static ProjectDescriptor project(String name, Path path) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                path,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }
}
