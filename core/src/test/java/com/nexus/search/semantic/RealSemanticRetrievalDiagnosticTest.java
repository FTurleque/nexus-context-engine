package com.nexus.search.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.semantic.SemanticBenchmarkSupport.Aggregate;
import com.nexus.search.semantic.SemanticBenchmarkSupport.OllamaSettings;
import com.nexus.search.semantic.SemanticBenchmarkSupport.RetrievalMeasurement;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
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
import java.util.Map;
import java.util.Set;

import static com.nexus.search.semantic.SemanticBenchmarkSupport.firstRelevantRank;
import static com.nexus.search.semantic.SemanticBenchmarkSupport.rankedRelativePaths;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "nexus.semantic.realDiagnostic.enabled", matches = "true")
class RealSemanticRetrievalDiagnosticTest {

    private static final int RETRIEVAL_LIMIT = 50;
    private static final List<Double> SEMANTIC_RRF_WEIGHTS =
            List.of(1.0d, 1.25d, 1.5d, 2.0d, 3.0d, 4.0d, 6.0d, 8.0d);

    @TempDir
    Path temporaryDirectory;

    @Test
    void diagnoseRawKnnVersusWeightedHybridRankingOnHermeticNexusSnapshot() throws Exception {
        Path corpusRoot = corpusRoot();
        assertTrue(Files.isDirectory(corpusRoot), "Le snapshot NEXUS doit exister : " + corpusRoot);

        OllamaSettings ollama = OllamaSettings.fromSystemProperties();
        OllamaEmbeddingProvider provider = ollama.provider();

        NexusPaths semanticPaths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        NexusApplication indexingApplication = NexusApplication.create(
                semanticPaths,
                SemanticSearchConfiguration.enabled(provider));
        ProjectDescriptor project = indexingApplication.registerProject(corpusRoot, "nexus-real-semantic-diagnostic");
        NexusApplication.IndexOperation indexing = indexingApplication.index(project.id(), true, false);

        SemanticSearchIndex rawIndex = new LuceneSemanticSearchIndex(semanticPaths, provider.dimensions());
        NexusApplication baselineApplication = NexusApplication.create(semanticPaths);
        Map<Double, NexusApplication> weightedApplications = new LinkedHashMap<>();
        for (double semanticWeight : SEMANTIC_RRF_WEIGHTS) {
            weightedApplications.put(
                    semanticWeight,
                    NexusApplication.create(
                            semanticPaths,
                            SemanticSearchConfiguration.enabled(provider, semanticWeight)));
        }

        List<QueryDiagnostic> diagnostics = new ArrayList<>();
        for (QueryCase queryCase : queries()) {
            diagnostics.add(runDiagnostic(
                    baselineApplication,
                    weightedApplications,
                    project,
                    corpusRoot,
                    provider,
                    rawIndex,
                    queryCase));
        }

        Aggregate rawAggregate = Aggregate.of(diagnostics.stream().map(QueryDiagnostic::raw).toList());
        Aggregate baselineAggregate = Aggregate.of(diagnostics.stream().map(QueryDiagnostic::baseline).toList());
        Map<Double, Aggregate> sweepAggregates = new LinkedHashMap<>();
        for (double semanticWeight : SEMANTIC_RRF_WEIGHTS) {
            sweepAggregates.put(
                    semanticWeight,
                    Aggregate.of(diagnostics.stream()
                            .map(diagnostic -> diagnostic.weightedHybrid().get(semanticWeight))
                            .toList()));
        }

        Double smallestWeightMatchingRaw = smallestWeightMatchingRaw(rawAggregate, sweepAggregates);
        double bestObservedWeight = bestObservedWeight(sweepAggregates);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("snapshotCommit", System.getProperty("nexus.semantic.realBenchmark.commit", "unknown"));
        report.put("modelId", provider.modelId());
        report.put("dimensions", provider.dimensions());
        report.put("endpoint", ollama.baseUri().toString());
        report.put("corpusFiles", indexing.report().scannedFiles());
        report.put("semanticIndexingMs", indexing.report().duration().toMillis());
        report.put("rawSemantic", summary(rawAggregate));
        report.put("baseline", summary(baselineAggregate));
        report.put("fusionSweep", sweepSummary(sweepAggregates));
        report.put("smallestWeightMatchingRawRecallAndHit", smallestWeightMatchingRaw);
        report.put("bestObservedWeightByRecallHitMrr", bestObservedWeight);
        report.put("queryResults", diagnostics.stream().map(RealSemanticRetrievalDiagnosticTest::toReport).toList());

        Path output = outputPath();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        Aggregate defaultHybrid = sweepAggregates.get(1.0d);
        Aggregate bestHybrid = sweepAggregates.get(bestObservedWeight);
        System.out.printf(
                java.util.Locale.ROOT,
                "NEXUS semantic diagnostic: raw recall@3=%.4f mrr@3=%.4f, RRF x1 recall@3=%.4f mrr@3=%.4f, best x%.2f recall@3=%.4f mrr@3=%.4f, output=%s%n",
                rawAggregate.recallAt3(),
                rawAggregate.mrrAt3(),
                defaultHybrid.recallAt3(),
                defaultHybrid.mrrAt3(),
                bestObservedWeight,
                bestHybrid.recallAt3(),
                bestHybrid.mrrAt3(),
                output);
    }

    private QueryDiagnostic runDiagnostic(
            NexusApplication baselineApplication,
            Map<Double, NexusApplication> weightedApplications,
            ProjectDescriptor project,
            Path corpusRoot,
            EmbeddingProvider provider,
            SemanticSearchIndex rawIndex,
            QueryCase queryCase) throws IOException {
        long rawStartedAt = System.nanoTime();
        float[] queryVector = provider.embed(queryCase.query());
        List<SemanticSearchHit> rawHits = rawIndex.search(project.id(), queryVector, RETRIEVAL_LIMIT);
        long rawDurationMs = elapsedMillis(rawStartedAt);

        List<String> rawPaths = rawHits.stream()
                .map(SemanticSearchHit::relativePath)
                .distinct()
                .toList();
        RetrievalMeasurement raw = RetrievalMeasurement.of(rawPaths, queryCase.relevantPaths(), rawDurationMs);

        RetrievalMeasurement baseline = runApplicationSearch(
                baselineApplication,
                project,
                corpusRoot,
                queryCase);

        Map<Double, RetrievalMeasurement> weightedHybrid = new LinkedHashMap<>();
        for (Map.Entry<Double, NexusApplication> entry : weightedApplications.entrySet()) {
            weightedHybrid.put(
                    entry.getKey(),
                    runApplicationSearch(entry.getValue(), project, corpusRoot, queryCase));
        }

        List<Map<String, Object>> rawTop = new ArrayList<>();
        for (SemanticSearchHit hit : rawHits.stream().limit(10).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", hit.relativePath());
            item.put("score", hit.score());
            rawTop.add(item);
        }

        return new QueryDiagnostic(queryCase, raw, baseline, Map.copyOf(weightedHybrid), List.copyOf(rawTop));
    }

    private RetrievalMeasurement runApplicationSearch(
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

    private static int rankAt50(RetrievalMeasurement measurement, Set<String> relevantPaths) {
        return firstRelevantRank(measurement.rankedPaths().stream().limit(RETRIEVAL_LIMIT).toList(), relevantPaths);
    }

    private static Map<String, Object> summary(Aggregate aggregate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("precisionAt3", aggregate.precisionAt3());
        result.put("recallAt3", aggregate.recallAt3());
        result.put("hitAt3", aggregate.hitAt3());
        result.put("mrrAt3", aggregate.mrrAt3());
        result.put("meanSearchMs", aggregate.meanSearchMs());
        return result;
    }

    private static Map<String, Object> sweepSummary(Map<Double, Aggregate> sweepAggregates) {
        Map<String, Object> result = new LinkedHashMap<>();
        sweepAggregates.forEach((weight, aggregate) -> result.put(formatWeight(weight), summary(aggregate)));
        return result;
    }

    private static Double smallestWeightMatchingRaw(
            Aggregate raw,
            Map<Double, Aggregate> sweepAggregates) {
        for (Map.Entry<Double, Aggregate> entry : sweepAggregates.entrySet()) {
            Aggregate candidate = entry.getValue();
            if (candidate.recallAt3() + 0.0000001d >= raw.recallAt3()
                    && candidate.hitAt3() + 0.0000001d >= raw.hitAt3()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static double bestObservedWeight(Map<Double, Aggregate> sweepAggregates) {
        return sweepAggregates.entrySet().stream()
                .sorted((left, right) -> {
                    int byRecall = Double.compare(right.getValue().recallAt3(), left.getValue().recallAt3());
                    if (byRecall != 0) {
                        return byRecall;
                    }
                    int byHit = Double.compare(right.getValue().hitAt3(), left.getValue().hitAt3());
                    if (byHit != 0) {
                        return byHit;
                    }
                    int byMrr = Double.compare(right.getValue().mrrAt3(), left.getValue().mrrAt3());
                    if (byMrr != 0) {
                        return byMrr;
                    }
                    int byPrecision = Double.compare(
                            right.getValue().precisionAt3(),
                            left.getValue().precisionAt3());
                    return byPrecision != 0 ? byPrecision : Double.compare(left.getKey(), right.getKey());
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> toReport(QueryDiagnostic diagnostic) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", diagnostic.queryCase().query());
        result.put("relevantPaths", diagnostic.queryCase().relevantPaths().stream().sorted().toList());
        result.put("rawRankAt3", diagnostic.raw().rankAt3());
        result.put("rawRankAt50", rankAt50(diagnostic.raw(), diagnostic.queryCase().relevantPaths()));
        result.put("baselineRankAt3", diagnostic.baseline().rankAt3());
        result.put("baselineRankAt50", rankAt50(diagnostic.baseline(), diagnostic.queryCase().relevantPaths()));
        result.put("rawTop", diagnostic.rawTop());
        result.put("baselineTop", diagnostic.baseline().rankedPaths().stream().limit(10).toList());

        Map<String, Object> sweep = new LinkedHashMap<>();
        diagnostic.weightedHybrid().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    RetrievalMeasurement measurement = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rankAt3", measurement.rankAt3());
                    item.put("rankAt50", rankAt50(measurement, diagnostic.queryCase().relevantPaths()));
                    item.put("top", measurement.rankedPaths().stream().limit(10).toList());
                    item.put("searchMs", measurement.durationMs());
                    sweep.put(formatWeight(entry.getKey()), item);
                });
        result.put("weightedRrf", sweep);
        result.put("rawSearchMs", diagnostic.raw().durationMs());
        result.put("baselineSearchMs", diagnostic.baseline().durationMs());
        return result;
    }

    private static List<QueryCase> queries() {
        return List.of(
                new QueryCase(
                        "generated lookup state should be disposable and recoverable from the authoritative metadata store",
                        Set.of("docs/architecture/adr/DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md")),
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
                                "docs/architecture/adr/BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md")),
                new QueryCase(
                        "retain only the most useful fragments that fit inside a bounded language-model input allowance",
                        Set.of(
                                "docs/architecture/adr/BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md",
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
                "nexus.semantic.realDiagnostic.output",
                "target/iteration-17-real-semantic-diagnostic.json");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static String formatWeight(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private record QueryCase(String query, Set<String> relevantPaths) {
        private QueryCase {
            relevantPaths = Set.copyOf(relevantPaths);
        }
    }

    private record QueryDiagnostic(
            QueryCase queryCase,
            RetrievalMeasurement raw,
            RetrievalMeasurement baseline,
            Map<Double, RetrievalMeasurement> weightedHybrid,
            List<Map<String, Object>> rawTop) {
    }
}
