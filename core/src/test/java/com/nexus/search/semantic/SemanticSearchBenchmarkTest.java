package com.nexus.search.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.semantic.SemanticBenchmarkSupport.Aggregate;
import com.nexus.search.semantic.SemanticBenchmarkSupport.OllamaSettings;
import com.nexus.search.semantic.SemanticBenchmarkSupport.RetrievalMeasurement;
import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nexus.search.semantic.SemanticBenchmarkSupport.K;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.directorySize;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.isLocalEndpoint;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.measurementSummary;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.rankedRelativePaths;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfSystemProperty(named = "nexus.semantic.benchmark.enabled", matches = "true")
class SemanticSearchBenchmarkTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void compareLexicalBaselineWithOptionalSemanticSearch() throws Exception {
        Path corpusRoot = createCorpus();
        OllamaSettings ollama = OllamaSettings.fromSystemProperties();
        OllamaEmbeddingProvider provider = ollama.provider();

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
            RetrievalMeasurement baselineResult = runQuery(baseline, baselineProject, corpusRoot, queryCase);
            RetrievalMeasurement semanticResult = runQuery(semantic, semanticProject, corpusRoot, queryCase);
            assertFalse(semanticResult.rankedPaths().isEmpty(), "La recherche sémantique doit retourner des candidats");
            measurements.add(new QueryMeasurement(queryCase, baselineResult, semanticResult));
        }

        Aggregate baselineAggregate = Aggregate.of(measurements.stream().map(QueryMeasurement::baseline).toList());
        Aggregate semanticAggregate = Aggregate.of(measurements.stream().map(QueryMeasurement::semantic).toList());
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

    private RetrievalMeasurement runQuery(
            NexusApplication application,
            ProjectDescriptor project,
            Path corpusRoot,
            QueryCase queryCase) throws IOException {
        NexusApplication.SearchOperation operation = application.search(project.id(), queryCase.query(), 5, true);
        List<String> rankedPaths = rankedRelativePaths(operation, corpusRoot);
        return RetrievalMeasurement.of(rankedPaths, Set.of(queryCase.relevantPath()), operation.durationMs());
    }

    private static Map<String, Object> queryMeasurement(QueryMeasurement measurement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", measurement.queryCase().query());
        result.put("relevantPath", measurement.queryCase().relevantPath());
        result.put("baselineRank", measurement.baseline().rankAt3());
        result.put("semanticRank", measurement.semantic().rankAt3());
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

    private static Path outputPath() {
        String configured = System.getProperty(
                "nexus.semantic.benchmark.output",
                "target/iteration-17-semantic-benchmark.json");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private record QueryCase(String query, String relevantPath) {
    }

    private record QueryMeasurement(QueryCase queryCase, RetrievalMeasurement baseline, RetrievalMeasurement semantic) {
    }
}
