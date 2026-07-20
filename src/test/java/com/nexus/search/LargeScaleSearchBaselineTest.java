package com.nexus.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.index.IndexStatistics;
import com.nexus.project.ProjectDescriptor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Harness opt-in pour établir une baseline de passage à l'échelle sur des
 * repositories locaux réels. Il est ignoré lorsque la propriété
 * {@code nexus.baseline.projects} n'est pas fournie.
 */
class LargeScaleSearchBaselineTest {

    private static final int SEARCH_WARMUPS = 3;
    private static final int SEARCH_SAMPLES = 10;
    private static final int CONTEXT_WARMUPS = 1;
    private static final int CONTEXT_SAMPLES = 3;
    private static final int SEARCH_LIMIT = 20;
    private static final int CONTEXT_BUDGET = 1_200;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresConfiguredRepositories() throws Exception {
        String configuredProjects = System.getProperty("nexus.baseline.projects", "").trim();
        Assumptions.assumeFalse(
                configuredProjects.isBlank(),
                "Baseline opt-in : fournir -Dnexus.baseline.projects=repo1|repo2");

        List<Path> projectRoots = Arrays.stream(configuredProjects.split("\\|"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        assertFalse(projectRoots.isEmpty());
        for (Path projectRoot : projectRoots) {
            if (!Files.isDirectory(projectRoot)) {
                throw new IllegalArgumentException("Repository baseline introuvable : " + projectRoot);
            }
        }

        String query = System.getProperty("nexus.baseline.query", "SearchService").trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("nexus.baseline.query must not be blank");
        }
        Path output = Path.of(System.getProperty(
                        "nexus.baseline.output",
                        "target/iteration-16-baseline.json"))
                .toAbsolutePath()
                .normalize();

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        NexusApplication application = NexusApplication.create(paths);
        long usedMemoryBefore = usedHeapBytes();

        List<ProjectDescriptor> projects = new ArrayList<>();
        List<Map<String, Object>> projectMetrics = new ArrayList<>();
        long totalFiles = 0L;
        long totalSymbols = 0L;
        long totalRelations = 0L;
        long totalIndexBytes = 0L;
        long totalFullIndexMs = 0L;
        long totalIncrementalIndexMs = 0L;

        for (int index = 0; index < projectRoots.size(); index++) {
            Path projectRoot = projectRoots.get(index);
            String projectName = baselineProjectName(projectRoot, index);
            ProjectDescriptor project = application.registerProject(projectRoot, projectName);
            projects.add(project);

            NexusApplication.IndexOperation fullIndex = application.index(project.id(), true, false);
            NexusApplication.IndexOperation incrementalIndex = application.index(project.id(), false, false);
            IndexStatistics statistics = application.inspect(project.id());
            long indexBytes = directorySize(paths.projectLuceneIndex(project.id()));
            long fullIndexMs = fullIndex.report().duration().toMillis();
            long incrementalIndexMs = incrementalIndex.report().duration().toMillis();

            totalFiles += statistics.files();
            totalSymbols += statistics.symbols();
            totalRelations += statistics.relations();
            totalIndexBytes += indexBytes;
            totalFullIndexMs += fullIndexMs;
            totalIncrementalIndexMs += incrementalIndexMs;

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("projectId", project.id().toString());
            metrics.put("name", project.name());
            metrics.put("root", project.rootPath().toString());
            metrics.put("files", statistics.files());
            metrics.put("symbols", statistics.symbols());
            metrics.put("relations", statistics.relations());
            metrics.put("luceneIndexBytes", indexBytes);
            metrics.put("fullIndexMs", fullIndexMs);
            metrics.put("incrementalNoChangeMs", incrementalIndexMs);
            projectMetrics.add(metrics);
        }

        List<java.util.UUID> projectIds = projects.stream().map(ProjectDescriptor::id).toList();
        for (int index = 0; index < SEARCH_WARMUPS; index++) {
            application.searchAcrossProjects(projectIds, query, SEARCH_LIMIT, false);
        }
        List<Long> federatedSearchDurations = new ArrayList<>();
        for (int index = 0; index < SEARCH_SAMPLES; index++) {
            federatedSearchDurations.add(
                    application.searchAcrossProjects(projectIds, query, SEARCH_LIMIT, false).durationMs());
        }

        List<Long> contextDurations = new ArrayList<>();
        for (ProjectDescriptor project : projects) {
            for (int index = 0; index < CONTEXT_WARMUPS; index++) {
                application.context(project.id(), query, CONTEXT_BUDGET, Set.of(), Map.of(), false);
            }
            for (int index = 0; index < CONTEXT_SAMPLES; index++) {
                contextDurations.add(application.context(
                                project.id(),
                                query,
                                CONTEXT_BUDGET,
                                Set.of(),
                                Map.of(),
                                false)
                        .durationMs());
            }
        }

        long usedMemoryAfter = usedHeapBytes();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("query", query);
        report.put("repositoryCount", projects.size());
        report.put("totalFiles", totalFiles);
        report.put("totalSymbols", totalSymbols);
        report.put("totalRelations", totalRelations);
        report.put("totalLuceneIndexBytes", totalIndexBytes);
        report.put("totalFullIndexMs", totalFullIndexMs);
        report.put("totalIncrementalNoChangeMs", totalIncrementalIndexMs);
        report.put("federatedSearchP50Ms", percentile(federatedSearchDurations, 0.50d));
        report.put("federatedSearchP95Ms", percentile(federatedSearchDurations, 0.95d));
        report.put("contextP50Ms", percentile(contextDurations, 0.50d));
        report.put("contextP95Ms", percentile(contextDurations, 0.95d));
        report.put("usedHeapBeforeBytes", usedMemoryBefore);
        report.put("usedHeapAfterBytes", usedMemoryAfter);
        report.put("usedHeapDeltaBytes", usedMemoryAfter - usedMemoryBefore);
        report.put("searchWarmups", SEARCH_WARMUPS);
        report.put("searchSamples", SEARCH_SAMPLES);
        report.put("contextWarmupsPerProject", CONTEXT_WARMUPS);
        report.put("contextSamplesPerProject", CONTEXT_SAMPLES);
        report.put("contextTokenBudget", CONTEXT_BUDGET);
        report.put("projects", projectMetrics);
        report.put("qualityBaseline", "Execute GoldenSearchCorpusTest separately for precision@3 and recall@3");
        report.put("incrementalSmallDelta", "Not measured automatically because source repositories are never modified");

        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        System.out.printf(
                "NEXUS scale baseline: repositories=%d, files=%d, symbols=%d, relations=%d, indexBytes=%d, searchP50=%dms, searchP95=%dms, contextP50=%dms, contextP95=%dms%n",
                projects.size(),
                totalFiles,
                totalSymbols,
                totalRelations,
                totalIndexBytes,
                percentile(federatedSearchDurations, 0.50d),
                percentile(federatedSearchDurations, 0.95d),
                percentile(contextDurations, 0.50d),
                percentile(contextDurations, 0.95d));
        System.out.println("NEXUS scale baseline report: " + output);
    }

    private static String baselineProjectName(Path projectRoot, int index) {
        Path fileName = projectRoot.getFileName();
        String baseName = fileName == null ? "project" : fileName.toString();
        return "baseline-" + (index + 1) + "-" + baseName;
    }

    private static long directorySize(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (var paths = Files.walk(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (Exception exception) {
                            throw new IllegalStateException("Impossible de mesurer " + path, exception);
                        }
                    })
                    .sum();
        }
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
