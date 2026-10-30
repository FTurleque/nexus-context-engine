package com.nexus.search.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.semantic.RealSemanticBenchmarkCorpus.QueryCase;
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

import static com.nexus.search.semantic.SemanticBenchmarkSupport.K;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.directorySize;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.isLocalEndpoint;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.measurementSummary;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.rankedRelativePaths;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "nexus.semantic.realBenchmark.enabled", matches = "true")
class RealSemanticSearchBenchmarkTest {

    private static final int RETRIEVAL_LIMIT = 50;

    @TempDir
    Path temporaryDirectory;

    @Test
    void compareBaselineAndSemanticSearchOnHermeticNexusSnapshot() throws Exception {
        Path snapshotRoot = snapshotRoot();
        assertTrue(Files.isDirectory(snapshotRoot), "Le snapshot NEXUS doit exister : " + snapshotRoot);
        RealSemanticBenchmarkCorpus.Kind corpus = RealSemanticBenchmarkCorpus.Kind.fromSystemProperties();
        Path corpusRoot = corpus == RealSemanticBenchmarkCorpus.Kind.PR
                ? RealSemanticBenchmarkCorpus.materializePrCorpus(
                        snapshotRoot,
                        temporaryDirectory.resolve("corpus-pr"))
                : snapshotRoot;

        OllamaSettings ollama = OllamaSettings.fromSystemProperties();
        OllamaEmbeddingProvider provider = ollama.provider();

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
        for (QueryCase queryCase : RealSemanticBenchmarkCorpus.queries()) {
            RetrievalMeasurement baselineResult = runQuery(baseline, baselineProject, corpusRoot, queryCase);
            RetrievalMeasurement semanticResult = runQuery(semantic, semanticProject, corpusRoot, queryCase);
            measurements.add(new QueryMeasurement(queryCase, baselineResult, semanticResult));
        }

        Aggregate baselineAggregate = Aggregate.of(measurements.stream().map(QueryMeasurement::baseline).toList());
        Aggregate semanticAggregate = Aggregate.of(measurements.stream().map(QueryMeasurement::semantic).toList());
        long semanticIndexBytes = directorySize(semanticPaths.projectSemanticLuceneIndex(semanticProject.id()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("snapshotCommit", System.getProperty("nexus.semantic.realBenchmark.commit", "unknown"));
        report.put("modelId", provider.modelId());
        report.put("dimensions", provider.dimensions());
        report.put("endpoint", ollama.baseUri().toString());
        report.put("localEndpoint", isLocalEndpoint(ollama.baseUri()));
        report.put("corpus", corpus.id());
        report.put("corpusFiles", baselineIndex.report().scannedFiles());
        report.put("corpusSymbols", baselineIndex.report().statistics().symbols());
        report.put("corpusRelations", baselineIndex.report().statistics().relations());
        report.put("queries", RealSemanticBenchmarkCorpus.queries().size());
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
                "NEXUS real semantic benchmark (corpus %s): baseline mrr@3=%.4f recall@3=%.4f, semantic mrr@3=%.4f recall@3=%.4f, output=%s%n",
                corpus.id(),
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
        NexusApplication.SearchOperation operation = application.search(
                project.id(),
                queryCase.query(),
                RETRIEVAL_LIMIT,
                true);
        List<String> rankedPaths = rankedRelativePaths(operation, corpusRoot);
        return RetrievalMeasurement.of(rankedPaths, queryCase.relevantPaths(), operation.durationMs());
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
        result.put("baselineRank", measurement.baseline().rankAt3());
        result.put("semanticRank", measurement.semantic().rankAt3());
        result.put("baselineTop", measurement.baseline().rankedPaths().stream().limit(K).toList());
        result.put("semanticTop", measurement.semantic().rankedPaths().stream().limit(K).toList());
        result.put("baselineSearchMs", measurement.baseline().durationMs());
        result.put("semanticSearchMs", measurement.semantic().durationMs());
        return result;
    }

    private static Path snapshotRoot() {
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

    private record QueryMeasurement(QueryCase queryCase, RetrievalMeasurement baseline, RetrievalMeasurement semantic) {
    }
}
