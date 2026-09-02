package com.nexus.search.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "nexus.semantic.realBenchmark.enabled", matches = "true")
class RealSemanticSearchBenchmarkTest {

    private static final int K = 3;
    private static final int RETRIEVAL_LIMIT = 50;

    @TempDir
    Path temporaryDirectory;

    @Test
    void compareBaselineAndSemanticSearchOnHermeticNexusSnapshot() throws Exception {
        Path corpusRoot = corpusRoot();
        assertTrue(Files.isDirectory(corpusRoot), "Le snapshot NEXUS doit exister : " + corpusRoot);

        OllamaSettings ollama = OllamaSettings.fromSystemProperties();
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                ollama.baseUri(),
                ollama.model(),
                ollama.dimensions(),
                Duration.ofSeconds(ollama.timeoutSeconds()));

        NexusApplication baseline = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("baseline-home")));
        ProjectDescriptor baselineProject = baseline.registerProject(corpusRoot, "nexus-real-semantic-baseline");
        NexusApplication.IndexOperation baselineIndex = baseline.index(baselineProject.id(), true, false);

        NexusPaths semanticPaths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        NexusApplication semantic = NexusApplication.create(
                semanticPaths,
                SemanticSearchConfiguration.enabled(provider));
        ProjectDescriptor semanticProject = semantic.registerProject(corpusRoot, "nexus-real-semantic-enabled");
        NexusApplication.IndexOperation semanticIndex = semantic.index(semanticProject.id(), true, false);

        List<QueryMeasurement> measurements = new ArrayList<>();
        for (QueryCase queryCase : queries()) {
            QueryResult baselineResult = runQuery(baseline, baselineProject, corpusRoot, queryCase);
            QueryResult semanticResult = runQuery(semantic, semanticProject, corpusRoot, queryCase);
            measurements.add(new QueryMeasurement(queryCase, baselineResult, semanticResult));
        }

        Aggregate baselineAggregate = aggregate(measurements.stream().map(QueryMeasurement::baseline).toList());
        Aggregate semanticAggregate = aggregate(measurements.stream().map(QueryMeasurement::semantic).toList());
        long semanticIndexBytes = directorySize(semanticPaths.projectSemanticLuceneIndex(semanticProject.id()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("snapshotCommit", System.getProperty("nexus.semantic.realBenchmark.commit", "unknown"));
        report.put("modelId", provider.modelId());
        report.put("dimensions", provider.dimensions());
        report.put("endpoint", ollama.baseUri().toString());
        report.put("localEndpoint", isLocalEndpoint(ollama.baseUri()));
        report.put("corpusFiles", baselineIndex.report().scannedFiles());
        report.put("corpusSymbols", baselineIndex.report().statistics().symbols());
        report.put("corpusRelations", baselineIndex.report().statistics().relations());
        report.put("queries", queries().size());
        report.put("baseline", measurementSummary(
                baselineIndex.report().duration().toMillis(),
                0L,
                baselineAggregate));
        report.put("semantic", measurementSummary(
                semanticIndex.report().duration().toMillis(),
                semanticIndexBytes,
                semanticAggregate));
        report.put("delta", delta(baselineAggregate, semanticAggregate));
        report.put("queryResults", measurements.stream().map(RealSemanticSearchBenchmarkTest::queryMeasurement).toList());

        Path output = outputPath();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS real semantic benchmark: baseline mrr@3=%.4f recall@3=%.4f, semantic mrr@3=%.4f recall@3=%.4f, output=%s%n",
                baselineAggregate.mrrAt3(),
                baselineAggregate.recallAt3(),
                semanticAggregate.mrrAt3(),
                semanticAggregate.recallAt3(),
                output);
    }

    private QueryResult runQuery(
            NexusApplication application,
            ProjectDescriptor project,
            Path corpusRoot,
            QueryCase queryCase) throws IOException {
        NexusApplication.SearchOperation operation = application.search(
                project.id(),
                queryCase.query(),
                RETRIEVAL_LIMIT,
                true);
        List<String> rankedPaths = operation.results().stream()
                .map(RankedCandidate::candidate)
                .map(candidate -> normalize(corpusRoot.relativize(candidate.path())))
                .distinct()
                .toList();

        List<String> topK = rankedPaths.stream().limit(K).toList();
        long relevantInTopK = topK.stream().filter(queryCase.relevantPaths()::contains).count();
        int firstRelevantRank = firstRelevantRank(topK, queryCase.relevantPaths());
        return new QueryResult(
                rankedPaths,
                firstRelevantRank,
                (double) relevantInTopK / K,
                (double) relevantInTopK / queryCase.relevantPaths().size(),
                relevantInTopK > 0 ? 1.0d : 0.0d,
                firstRelevantRank > 0 ? 1.0d / firstRelevantRank : 0.0d,
                operation.durationMs());
    }

    private static int firstRelevantRank(List<String> rankedPaths, Set<String> relevantPaths) {
        for (int index = 0; index < rankedPaths.size(); index++) {
            if (relevantPaths.contains(rankedPaths.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    private static Aggregate aggregate(List<QueryResult> results) {
        return new Aggregate(
                mean(results.stream().map(QueryResult::precisionAt3).toList()),
                mean(results.stream().map(QueryResult::recallAt3).toList()),
                mean(results.stream().map(QueryResult::hitAt3).toList()),
                mean(results.stream().map(QueryResult::mrrAt3).toList()),
                meanLong(results.stream().map(QueryResult::durationMs).toList()));
    }

    private static Map<String, Object> measurementSummary(
            long indexingMs,
            long semanticIndexBytes,
            Aggregate aggregate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexingMs", indexingMs);
        result.put("semanticIndexBytes", semanticIndexBytes);
        result.put("precisionAt3", aggregate.precisionAt3());
        result.put("recallAt3", aggregate.recallAt3());
        result.put("hitAt3", aggregate.hitAt3());
        result.put("mrrAt3", aggregate.mrrAt3());
        result.put("meanSearchMs", aggregate.meanSearchMs());
        return result;
    }

    private static Map<String, Object> delta(Aggregate baseline, Aggregate semantic) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("precisionAt3", semantic.precisionAt3() - baseline.precisionAt3());
        result.put("recallAt3", semantic.recallAt3() - baseline.recallAt3());
        result.put("hitAt3", semantic.hitAt3() - baseline.hitAt3());
        result.put("mrrAt3", semantic.mrrAt3() - baseline.mrrAt3());
        result.put("meanSearchMs", semantic.meanSearchMs() - baseline.meanSearchMs());
        return result;
    }

    private static Map<String, Object> queryMeasurement(QueryMeasurement measurement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", measurement.queryCase().query());
        result.put("relevantPaths", measurement.queryCase().relevantPaths().stream().sorted().toList());
        result.put("baselineRank", measurement.baseline().firstRelevantRank());
        result.put("semanticRank", measurement.semantic().firstRelevantRank());
        result.put("baselineTop", measurement.baseline().rankedPaths().stream().limit(K).toList());
        result.put("semanticTop", measurement.semantic().rankedPaths().stream().limit(K).toList());
        result.put("baselineSearchMs", measurement.baseline().durationMs());
        result.put("semanticSearchMs", measurement.semantic().durationMs());
        return result;
    }

    private static List<QueryCase> queries() {
        return List.of(
                new QueryCase(
                        "generated lookup state should be disposable and recoverable from the authoritative metadata store",
                        Set.of("docs/adr/0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md")),
                new QueryCase(
                        "screen lightweight capability summaries before loading the full operating procedure",
                        Set.of("docs/developer/agent-skills.md")),
                new QueryCase(
                        "version-control activity should influence relevance while remaining completely offline and read-only",
                        Set.of("docs/developer/git-context.md")),
                new QueryCase(
                        "let agent clients invoke project search through a standard stdio tool bridge without coupling the engine core",
                        Set.of("docs/developer/mcp.md")),
                new QueryCase(
                        "combine results from several codebases while keeping every hit tied to its repository of origin",
                        Set.of(
                                "docs/developer/large-scale-search.md",
                                "docs/adr/0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md")),
                new QueryCase(
                        "retain only the most useful fragments that fit inside a bounded language-model input allowance",
                        Set.of(
                                "docs/adr/0013-construire-un-contextbundle-sous-budget-de-tokens.md",
                                "docs/developer/context-building.md")));
    }

    private static Path corpusRoot() {
        String configured = System.getProperty("nexus.semantic.realBenchmark.root");
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("nexus.semantic.realBenchmark.root doit pointer vers le snapshot NEXUS");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Path outputPath() {
        String configured = System.getProperty(
                "nexus.semantic.realBenchmark.output",
                "target/iteration-17-real-semantic-benchmark.json");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    }

    private static double meanLong(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0d);
    }

    private static long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new DirectorySizeException(exception);
                        }
                    })
                    .sum();
        } catch (DirectorySizeException exception) {
            throw exception.ioException();
        }
    }

    private static boolean isLocalEndpoint(URI baseUri) {
        String host = baseUri.getHost();
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1"));
    }

    private record QueryCase(String query, Set<String> relevantPaths) {
        private QueryCase {
            relevantPaths = Set.copyOf(relevantPaths);
        }
    }

    private record QueryResult(
            List<String> rankedPaths,
            int firstRelevantRank,
            double precisionAt3,
            double recallAt3,
            double hitAt3,
            double mrrAt3,
            long durationMs) {
    }

    private record QueryMeasurement(QueryCase queryCase, QueryResult baseline, QueryResult semantic) {
    }

    private record Aggregate(
            double precisionAt3,
            double recallAt3,
            double hitAt3,
            double mrrAt3,
            double meanSearchMs) {
    }

    private record OllamaSettings(URI baseUri, String model, int dimensions, long timeoutSeconds) {
        private static OllamaSettings fromSystemProperties() {
            return new OllamaSettings(
                    URI.create(System.getProperty(
                            "nexus.semantic.ollama.baseUri",
                            OllamaEmbeddingProvider.DEFAULT_BASE_URI.toString())),
                    System.getProperty("nexus.semantic.ollama.model", OllamaEmbeddingProvider.DEFAULT_MODEL),
                    Integer.getInteger("nexus.semantic.ollama.dimensions", OllamaEmbeddingProvider.DEFAULT_DIMENSIONS),
                    Long.getLong("nexus.semantic.ollama.timeoutSeconds", OllamaEmbeddingProvider.DEFAULT_TIMEOUT.toSeconds()));
        }
    }

    private static final class DirectorySizeException extends RuntimeException {
        private final IOException ioException;

        private DirectorySizeException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}
