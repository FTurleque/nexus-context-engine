package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.context.ContextBuilder;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.context.ContextRequest;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextService;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the federated work budget at the real 200k-token ceiling. */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class FederatedContextBudgetScaleBenchmarkTest {

    private static final int PROJECTS = 100;
    private static final int GLOBAL_BUDGET = 200_000;
    private static final int EXPECTED_OVERFETCH_FACTOR = 3;
    private static final int ITEM_TOKENS = 1_000;

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundsWorkAtMaximumPortfolioAndBudget() throws Exception {
        List<ProjectDescriptor> projects = new ArrayList<>();
        for (int index = 0; index < PROJECTS; index++) {
            projects.add(new ProjectDescriptor(
                    UUID.nameUUIDFromBytes(("scale-project-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "scale-project-" + index,
                    temporaryDirectory.resolve("project-" + index).toAbsolutePath().normalize(),
                    ProjectSourceType.LOCAL,
                    Set.of("java"),
                    Set.of(),
                    Instant.EPOCH,
                    IndexStatus.READY));
        }

        RecordingContextBuilder builder = new RecordingContextBuilder(projects);
        FederatedContextService service = new FederatedContextService(builder);

        long started = System.nanoTime();
        FederatedContextBundle first = service.build(
                projects,
                "shared-scale-query",
                GLOBAL_BUDGET,
                Set.of(),
                Map.of(),
                false);
        long firstDurationMs = elapsedMillis(started);
        List<Integer> firstBudgets = builder.budgets();

        builder.reset();
        FederatedContextBundle second = service.build(
                projects,
                "shared-scale-query",
                GLOBAL_BUDGET,
                Set.of(),
                Map.of(),
                false);

        int candidateBudgetTotal = ((Number) first.metadata().get("candidateBudgetTotal")).intValue();
        assertEquals(GLOBAL_BUDGET * EXPECTED_OVERFETCH_FACTOR, candidateBudgetTotal);
        assertEquals(EXPECTED_OVERFETCH_FACTOR,
                ((Number) first.metadata().get("candidateBudgetOverfetchFactor")).intValue());
        assertEquals(candidateBudgetTotal, firstBudgets.stream().mapToInt(Integer::intValue).sum());
        assertTrue(firstBudgets.stream().allMatch(budget -> budget <= 6_000));
        assertTrue(first.estimatedTokens() <= GLOBAL_BUDGET);
        assertEquals(GLOBAL_BUDGET, first.estimatedTokens());
        assertEquals(
                first.items().stream().map(item -> item.project().id() + ":" + item.item().path()).toList(),
                second.items().stream().map(item -> item.project().id() + ":" + item.item().path()).toList());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("projects", PROJECTS);
        report.put("globalBudget", GLOBAL_BUDGET);
        report.put("candidateBudgetTotal", candidateBudgetTotal);
        report.put("candidateBudgetMultiplier", (double) candidateBudgetTotal / GLOBAL_BUDGET);
        report.put("maxPerProjectCandidateBudget", firstBudgets.stream().mapToInt(Integer::intValue).max().orElse(0));
        report.put("selectedTokens", first.estimatedTokens());
        report.put("selectedItems", first.items().size());
        report.put("durationMs", firstDurationMs);
        report.put("deterministic", true);

        Path output = Path.of(System.getProperty(
                        "nexus.federated.scale.benchmark.output",
                        "target/federated-budget-scale-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS federated budget benchmark: projects=%d global=%d candidateTotal=%d selected=%d duration=%dms output=%s%n",
                PROJECTS,
                GLOBAL_BUDGET,
                candidateBudgetTotal,
                first.estimatedTokens(),
                firstDurationMs,
                output);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static final class RecordingContextBuilder implements ContextBuilder {
        private final Map<UUID, ProjectDescriptor> projects;
        private final List<Integer> budgets = new ArrayList<>();

        private RecordingContextBuilder(List<ProjectDescriptor> projects) {
            Map<UUID, ProjectDescriptor> indexed = new LinkedHashMap<>();
            projects.forEach(project -> indexed.put(project.id(), project));
            this.projects = Map.copyOf(indexed);
        }

        @Override
        public ContextBundle build(ContextRequest request) {
            budgets.add(request.tokenBudget());
            ProjectDescriptor project = projects.get(request.projectId());
            int itemCount = request.tokenBudget() / ITEM_TOKENS;
            List<ContextItem> items = new ArrayList<>(itemCount);
            for (int index = 0; index < itemCount; index++) {
                Path path = project.rootPath().resolve("src/Item" + index + ".java");
                items.add(new ContextItem(
                        CandidateType.FILE,
                        path,
                        null,
                        1,
                        1,
                        project.id() + "-content-" + index,
                        1.0d - (index * 0.001d),
                        Map.of(),
                        List.of("scale"),
                        ITEM_TOKENS,
                        false));
            }
            return new ContextBundle(
                    items,
                    request.tokenBudget(),
                    itemCount * ITEM_TOKENS,
                    List.of(),
                    Map.of());
        }

        private List<Integer> budgets() {
            return List.copyOf(budgets);
        }

        private void reset() {
            budgets.clear();
        }
    }
}
