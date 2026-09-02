package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.context.source.skill.AiSkillsRegistryProvider;
import com.nexus.context.source.skill.SkillProviderResult;
import com.nexus.context.source.skill.SkillSourceQuery;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises real filesystem-backed native skill discovery under the shared work budget. */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class NativeContextDiscoveryBudgetBenchmarkTest {

    private static final int SKILLS = 1_000;
    private static final int EXPECTED_VISITED_ENTRIES = 1 + (SKILLS * 2);
    private static final long MAX_BENCHMARK_DURATION_MS = 10_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundsAndMeasuresNativeSkillDiscoveryWork() throws Exception {
        Path registry = temporaryDirectory.resolve(".nexus/registry/skills");
        for (int index = 0; index < SKILLS; index++) {
            Path definition = registry.resolve("skill-%04d/SKILL.md".formatted(index));
            Files.createDirectories(definition.getParent());
            Files.writeString(definition, """
                    ---
                    name: skill-%04d
                    description: Synthetic native discovery benchmark skill %04d.
                    ---
                    body-not-loaded-during-discovery
                    """.formatted(index, index), StandardCharsets.UTF_8);
        }

        ProjectDescriptor project = new ProjectDescriptor(
                UUID.nameUUIDFromBytes("native-discovery-scale".getBytes(StandardCharsets.UTF_8)),
                "native-discovery-scale",
                temporaryDirectory.toAbsolutePath().normalize(),
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                Instant.EPOCH,
                IndexStatus.READY);

        ContextDiscoveryLimits limits = new ContextDiscoveryLimits(
                EXPECTED_VISITED_ENTRIES,
                SKILLS,
                4L * 1024L * 1024L,
                15_000L);
        ContextDiscoveryBudget budget = limits.newBudget();
        AiSkillsRegistryProvider provider = new AiSkillsRegistryProvider();

        long started = System.nanoTime();
        SkillProviderResult first = provider.discover(new SkillSourceQuery(project, false, budget));
        long durationMs = elapsedMillis(started);
        ContextDiscoveryBudget.Snapshot snapshot = budget.snapshot();

        assertEquals(SKILLS, first.skills().size());
        assertTrue(first.diagnostics().isEmpty());
        assertEquals(EXPECTED_VISITED_ENTRIES, snapshot.visitedEntries());
        assertEquals(SKILLS, snapshot.candidateResources());
        assertTrue(snapshot.cumulativeBytes() > 0L);
        assertTrue(snapshot.cumulativeBytes() <= limits.maxCumulativeBytes());
        assertTrue(durationMs <= MAX_BENCHMARK_DURATION_MS,
                "native discovery exceeded the regression budget: " + durationMs + " ms");

        ContextDiscoveryBudget secondBudget = limits.newBudget();
        SkillProviderResult second = provider.discover(new SkillSourceQuery(project, false, secondBudget));
        assertEquals(
                first.skills().stream().map(skill -> skill.name() + ":" + skill.definitionPath()).toList(),
                second.skills().stream().map(skill -> skill.name() + ":" + skill.definitionPath()).toList(),
                "discovery ordering must remain deterministic at the exact configured boundary");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("skills", SKILLS);
        report.put("visitedEntries", snapshot.visitedEntries());
        report.put("candidateResources", snapshot.candidateResources());
        report.put("cumulativeBytes", snapshot.cumulativeBytes());
        report.put("durationMs", durationMs);
        report.put("maxDurationMs", MAX_BENCHMARK_DURATION_MS);
        report.put("deterministic", true);

        Path output = Path.of(System.getProperty(
                        "nexus.native.discovery.scale.benchmark.output",
                        "target/native-discovery-scale-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS native discovery benchmark: skills=%d visited=%d candidates=%d bytes=%d duration=%dms output=%s%n",
                SKILLS,
                snapshot.visitedEntries(),
                snapshot.candidateResources(),
                snapshot.cumulativeBytes(),
                durationMs,
                output);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
