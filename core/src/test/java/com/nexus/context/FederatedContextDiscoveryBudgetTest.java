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
    void sharesPhysicalWorkBudgetsAcrossEveryFederatedProject() {
        ProjectDescriptor first = project("first", root.resolve("first"));
        ProjectDescriptor second = project("second", root.resolve("second"));
        List<ContextDiscoveryBudget> observedDiscoveryBudgets = new ArrayList<>();
        List<ContextMaterializationBudget> observedMaterializationBudgets = new ArrayList<>();

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
                observedMaterializationBudgets.add(materializationBudget);
                observedDiscoveryBudgets.add(discoveryBudget);
                return new ContextBundle(List.of(), request.tokenBudget(), 0, List.of(), Map.of());
            }
        };

        FederatedContextBundle bundle = new FederatedContextService(builder).build(
                List.of(first, second), "task", 200, Set.of(), Map.of(), false);

        assertEquals(2, observedDiscoveryBudgets.size());
        assertSame(observedDiscoveryBudgets.get(0), observedDiscoveryBudgets.get(1));
        assertEquals(observedDiscoveryBudgets.get(0).limits(), bundle.metadata().get("nativeDiscoveryLimits"));

        ContextDiscoveryBudget.Snapshot recordedDiscovery =
                (ContextDiscoveryBudget.Snapshot) bundle.metadata().get("nativeDiscoveryWork");
        ContextDiscoveryBudget.Snapshot currentDiscovery = observedDiscoveryBudgets.get(0).snapshot();
        assertEquals(recordedDiscovery.visitedEntries(), currentDiscovery.visitedEntries());
        assertEquals(recordedDiscovery.candidateResources(), currentDiscovery.candidateResources());
        assertEquals(recordedDiscovery.cumulativeBytes(), currentDiscovery.cumulativeBytes());
        assertTrue(currentDiscovery.elapsedMillis() >= recordedDiscovery.elapsedMillis());

        assertEquals(2, observedMaterializationBudgets.size());
        assertSame(observedMaterializationBudgets.get(0), observedMaterializationBudgets.get(1));
        assertEquals(
                observedMaterializationBudgets.get(0).limits(),
                bundle.metadata().get("taskMaterializationLimits"));

        ContextMaterializationBudget.Snapshot recordedMaterialization =
                (ContextMaterializationBudget.Snapshot) bundle.metadata().get("taskMaterializationWork");
        ContextMaterializationBudget.Snapshot currentMaterialization = observedMaterializationBudgets.get(0).snapshot();
        assertEquals(recordedMaterialization.openedFiles(), currentMaterialization.openedFiles());
        assertEquals(recordedMaterialization.cumulativeBytes(), currentMaterialization.cumulativeBytes());
        assertEquals(recordedMaterialization.remainingFiles(), currentMaterialization.remainingFiles());
        assertTrue(currentMaterialization.elapsedMillis() >= recordedMaterialization.elapsedMillis());
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
