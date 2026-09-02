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

import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfSystemProperty(named = "nexus.semantic.benchmark.enabled", matches = "true")
class SemanticSearchBenchmarkTest {

    private static final int K = 3;

    @TempDir
    Path temporaryDirectory;

    @Test
    void compareLexicalBaselineWithOptionalSemanticSearch() throws Exception {
        Path corpusRoot = createCorpus();
        OllamaSettings ollama = OllamaSettings.fromSystemProperties();
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                ollama.baseUri(),
                ollama.model(),
                ollama.dimensions(),
                Duration.ofSeconds(ollama.timeoutSeconds()));

        NexusApplication baseline = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("baseline-home")));
        ProjectDescriptor baselineProject = baseline.registerProject(corpusRoot, "semantic-benchmark-baseline");
        NexusApplication.IndexOperation baselineIndex = baseline.index(baselineProject.id(), true, false);

        NexusPaths semanticPaths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        NexusApplication semantic = NexusApplication.create(
                semanticPaths,
                SemanticSearchConfiguration.enabled(provider));
        ProjectDescriptor semanticProject = semantic.registerProject(corpusRoot, "semantic-benchmark-enabled");
        NexusApplication.IndexOperation semanticIndex = semantic.index(semanticProject.id(), true, false);

        List<QueryCase> queries = queries();
        List<QueryMeasurement> measurements = new ArrayList<>();
        for (QueryCase queryCase : queries) {
            QueryResult baselineResult = runQuery(baseline, baselineProject, corpusRoot, queryCase);
            QueryResult semanticResult = runQuery(semantic, semanticProject, corpusRoot, queryCase);
            assertFalse(semanticResult.rankedPaths().isEmpty(), "La recherche sémantique doit retourner des candidats");
            measurements.add(new QueryMeasurement(queryCase, baselineResult, semanticResult));
        }

        Aggregate baselineAggregate = aggregate(measurements.stream().map(QueryMeasurement::baseline).toList());
        Aggregate semanticAggregate = aggregate(measurements.stream().map(QueryMeasurement::semantic).toList());
        long semanticIndexBytes = directorySize(semanticPaths.projectSemanticLuceneIndex(semanticProject.id()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("modelId", provider.modelId());
        report.put("dimensions", provider.dimensions());
        report.put("endpoint", ollama.baseUri().toString());
        report.put("localEndpoint", isLocalEndpoint(ollama.baseUri()));
        report.put("corpusDocuments", 8);
        report.put("queries", queries.size());
        report.put("baseline", measurementSummary(baselineIndex.report().duration().toMillis(), 0L, baselineAggregate));
        report.put("semantic", measurementSummary(
                semanticIndex.report().duration().toMillis(),
                semanticIndexBytes,
                semanticAggregate));
        report.put("delta", Map.of(
                "precisionAt3", semanticAggregate.precisionAt3() - baselineAggregate.precisionAt3(),
                "recallAt3", semanticAggregate.recallAt3() - baselineAggregate.recallAt3(),
                "hitAt3", semanticAggregate.hitAt3() - baselineAggregate.hitAt3(),
                "mrrAt3", semanticAggregate.mrrAt3() - baselineAggregate.mrrAt3(),
                "meanSearchMs", semanticAggregate.meanSearchMs() - baselineAggregate.meanSearchMs()));
        report.put("queryResults", measurements.stream().map(SemanticSearchBenchmarkTest::queryMeasurement).toList());

        Path output = outputPath();
        Files.createDirectories(output.getParent());
        ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS semantic benchmark: baseline mrr@3=%.4f recall@3=%.4f, semantic mrr@3=%.4f recall@3=%.4f, output=%s%n",
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
        NexusApplication.SearchOperation operation = application.search(project.id(), queryCase.query(), 5, true);
        List<String> rankedPaths = operation.results().stream()
                .map(RankedCandidate::candidate)
                .map(candidate -> normalize(corpusRoot.relativize(candidate.path())))
                .distinct()
                .toList();
        int rank = firstRelevantRank(rankedPaths, queryCase.relevantPath(), K);
        return new QueryResult(
                rankedPaths,
                rank,
                rank > 0 ? 1.0d / K : 0.0d,
                rank > 0 ? 1.0d : 0.0d,
                rank > 0 ? 1.0d : 0.0d,
                rank > 0 ? 1.0d / rank : 0.0d,
                operation.durationMs());
    }

    private static int firstRelevantRank(List<String> rankedPaths, String relevantPath, int k) {
        for (int index = 0; index < Math.min(k, rankedPaths.size()); index++) {
            if (rankedPaths.get(index).equals(relevantPath)) {
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

    private static Map<String, Object> measurementSummary(long indexingMs, long semanticIndexBytes, Aggregate aggregate) {
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

    private static Map<String, Object> queryMeasurement(QueryMeasurement measurement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", measurement.queryCase().query());
        result.put("relevantPath", measurement.queryCase().relevantPath());
        result.put("baselineRank", measurement.baseline().firstRelevantRank());
        result.put("semanticRank", measurement.semantic().firstRelevantRank());
        result.put("baselineTop", measurement.baseline().rankedPaths().stream().limit(K).toList());
        result.put("semanticTop", measurement.semantic().rankedPaths().stream().limit(K).toList());
        result.put("baselineSearchMs", measurement.baseline().durationMs());
        result.put("semanticSearchMs", measurement.semantic().durationMs());
        return result;
    }

    private Path createCorpus() throws IOException {
        Path root = temporaryDirectory.resolve("semantic-corpus");
        write(root, "docs/cache-stampede.md",
                "Coalesce concurrent cache misses behind a single in-flight loader so duplicate backend work is avoided.");
        write(root, "docs/hexagonal-boundaries.md",
                "Inbound ports expose use cases while outbound adapters implement persistence and transport details.");
        write(root, "docs/token-budget.md",
                "Fragment selection is capped by an estimated token budget and preserves diversity among context sources.");
        write(root, "docs/incremental-index.md",
                "Content hashes drive delta indexing. Unchanged paths are skipped and only modified entries are refreshed.");
        write(root, "docs/local-privacy.md",
                "Local-first retrieval operates without network egress and keeps repository material on the developer machine.");
        write(root, "docs/ui-layout.md",
                "Responsive widgets are arranged in rows and columns with accessible labels and keyboard navigation.");
        write(root, "docs/release-notes.md",
                "Release notes summarize version numbers, compatibility changes and migration instructions.");
        write(root, "docs/database-schema.md",
                "Relational tables use primary keys, foreign keys and explicit transaction boundaries.");
        return root;
    }

    private static List<QueryCase> queries() {
        return List.of(
                new QueryCase("stop many callers from hammering storage for the same missing key", "docs/cache-stampede.md"),
                new QueryCase("keep business rules independent from frameworks and databases", "docs/hexagonal-boundaries.md"),
                new QueryCase("fit only the most useful evidence inside the model input limit", "docs/token-budget.md"),
                new QueryCase("avoid rescanning everything when a developer edits two files", "docs/incremental-index.md"),
                new QueryCase("prevent proprietary source code from being sent to hosted services", "docs/local-privacy.md"));
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
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

    private static Path outputPath() {
        String configured = System.getProperty(
                "nexus.semantic.benchmark.output",
                "target/iteration-17-semantic-benchmark.json");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private record QueryCase(String query, String relevantPath) {
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
