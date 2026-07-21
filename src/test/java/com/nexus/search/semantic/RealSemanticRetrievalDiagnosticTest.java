package com.nexus.search.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "nexus.semantic.realDiagnostic.enabled", matches = "true")
class RealSemanticRetrievalDiagnosticTest {

    private static final int K = 3;
    private static final int RETRIEVAL_LIMIT = 50;

    @TempDir
    Path temporaryDirectory;

    @Test
    void diagnoseRawKnnVersusHybridRankingOnHermeticNexusSnapshot() throws Exception {
        Path corpusRoot = corpusRoot();
        assertTrue(Files.isDirectory(corpusRoot), "Le snapshot NEXUS doit exister : " + corpusRoot);

        OllamaSettings ollama = OllamaSettings.fromSystemProperties();
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                ollama.baseUri(),
                ollama.model(),
                ollama.dimensions(),
                Duration.ofSeconds(ollama.timeoutSeconds()));

        NexusPaths semanticPaths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        NexusApplication semantic = NexusApplication.create(
                semanticPaths,
                SemanticSearchConfiguration.enabled(provider));
        ProjectDescriptor project = semantic.registerProject(corpusRoot, "nexus-real-semantic-diagnostic");
        NexusApplication.IndexOperation indexing = semantic.index(project.id(), true, false);

        SemanticSearchIndex rawIndex = new LuceneSemanticSearchIndex(semanticPaths, provider.dimensions());
        List<QueryDiagnostic> diagnostics = new ArrayList<>();
        for (QueryCase queryCase : queries()) {
            diagnostics.add(runDiagnostic(semantic, project, corpusRoot, provider, rawIndex, queryCase));
        }

        Aggregate rawAggregate = aggregate(diagnostics.stream().map(QueryDiagnostic::raw).toList());
        Aggregate hybridAggregate = aggregate(diagnostics.stream().map(QueryDiagnostic::hybrid).toList());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("snapshotCommit", System.getProperty("nexus.semantic.realBenchmark.commit", "unknown"));
        report.put("modelId", provider.modelId());
        report.put("dimensions", provider.dimensions());
        report.put("endpoint", ollama.baseUri().toString());
        report.put("corpusFiles", indexing.report().scannedFiles());
        report.put("semanticIndexingMs", indexing.report().duration().toMillis());
        report.put("rawSemantic", summary(rawAggregate));
        report.put("hybrid", summary(hybridAggregate));
        report.put("queryResults", diagnostics.stream().map(RealSemanticRetrievalDiagnosticTest::toReport).toList());

        Path output = outputPath();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                java.util.Locale.ROOT,
                "NEXUS semantic diagnostic: raw mrr@3=%.4f recall@3=%.4f, hybrid mrr@3=%.4f recall@3=%.4f, output=%s%n",
                rawAggregate.mrrAt3(),
                rawAggregate.recallAt3(),
                hybridAggregate.mrrAt3(),
                hybridAggregate.recallAt3(),
                output);
    }

    private QueryDiagnostic runDiagnostic(
            NexusApplication application,
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
        RetrievalResult raw = result(rawPaths, queryCase.relevantPaths(), rawDurationMs);

        NexusApplication.SearchOperation hybridOperation = application.search(
                project.id(),
                queryCase.query(),
                RETRIEVAL_LIMIT,
                true);
        List<String> hybridPaths = hybridOperation.results().stream()
                .map(RankedCandidate::candidate)
                .map(candidate -> normalize(corpusRoot.relativize(candidate.path())))
                .distinct()
                .toList();
        RetrievalResult hybrid = result(hybridPaths, queryCase.relevantPaths(), hybridOperation.durationMs());

        List<Map<String, Object>> rawTop = new ArrayList<>();
        for (SemanticSearchHit hit : rawHits.stream().limit(10).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", hit.relativePath());
            item.put("score", hit.score());
            rawTop.add(item);
        }

        return new QueryDiagnostic(queryCase, raw, hybrid, List.copyOf(rawTop));
    }

    private static RetrievalResult result(List<String> rankedPaths, Set<String> relevantPaths, long durationMs) {
        List<String> topK = rankedPaths.stream().limit(K).toList();
        long relevantInTopK = topK.stream().filter(relevantPaths::contains).count();
        int rankAt3 = firstRelevantRank(topK, relevantPaths);
        int rankAt50 = firstRelevantRank(rankedPaths.stream().limit(RETRIEVAL_LIMIT).toList(), relevantPaths);
        return new RetrievalResult(
                rankedPaths,
                rankAt3,
                rankAt50,
                (double) relevantInTopK / K,
                (double) relevantInTopK / relevantPaths.size(),
                relevantInTopK > 0 ? 1.0d : 0.0d,
                rankAt3 > 0 ? 1.0d / rankAt3 : 0.0d,
                durationMs);
    }

    private static int firstRelevantRank(List<String> rankedPaths, Set<String> relevantPaths) {
        for (int index = 0; index < rankedPaths.size(); index++) {
            if (relevantPaths.contains(rankedPaths.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    private static Aggregate aggregate(List<RetrievalResult> results) {
        return new Aggregate(
                mean(results.stream().map(RetrievalResult::precisionAt3).toList()),
                mean(results.stream().map(RetrievalResult::recallAt3).toList()),
                mean(results.stream().map(RetrievalResult::hitAt3).toList()),
                mean(results.stream().map(RetrievalResult::mrrAt3).toList()),
                meanLong(results.stream().map(RetrievalResult::durationMs).toList()));
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

    private static Map<String, Object> toReport(QueryDiagnostic diagnostic) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", diagnostic.queryCase().query());
        result.put("relevantPaths", diagnostic.queryCase().relevantPaths().stream().sorted().toList());
        result.put("rawRankAt3", diagnostic.raw().rankAt3());
        result.put("rawRankAt50", diagnostic.raw().rankAt50());
        result.put("hybridRankAt3", diagnostic.hybrid().rankAt3());
        result.put("hybridRankAt50", diagnostic.hybrid().rankAt50());
        result.put("rawTop", diagnostic.rawTop());
        result.put("hybridTop", diagnostic.hybrid().rankedPaths().stream().limit(10).toList());
        result.put("rawSearchMs", diagnostic.raw().durationMs());
        result.put("hybridSearchMs", diagnostic.hybrid().durationMs());
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
                "nexus.semantic.realDiagnostic.output",
                "target/iteration-17-real-semantic-diagnostic.json");
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

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private record QueryCase(String query, Set<String> relevantPaths) {
        private QueryCase {
            relevantPaths = Set.copyOf(relevantPaths);
        }
    }

    private record RetrievalResult(
            List<String> rankedPaths,
            int rankAt3,
            int rankAt50,
            double precisionAt3,
            double recallAt3,
            double hitAt3,
            double mrrAt3,
            long durationMs) {
    }

    private record QueryDiagnostic(
            QueryCase queryCase,
            RetrievalResult raw,
            RetrievalResult hybrid,
            List<Map<String, Object>> rawTop) {
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
}
