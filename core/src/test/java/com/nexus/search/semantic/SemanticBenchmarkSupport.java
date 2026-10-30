package com.nexus.search.semantic;

import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outils communs aux tests de mesure sémantique opt-in : réglages Ollama lus dans
 * les propriétés système {@code nexus.semantic.ollama.*}, métriques à {@code K = 3}
 * (precision, recall, hit, MRR), taille de l'index sémantique et nature de l'endpoint.
 */
final class SemanticBenchmarkSupport {

    static final int K = 3;

    private SemanticBenchmarkSupport() {
    }

    record OllamaSettings(URI baseUri, String model, int dimensions, long timeoutSeconds) {

        static OllamaSettings fromSystemProperties() {
            return new OllamaSettings(
                    URI.create(System.getProperty(
                            "nexus.semantic.ollama.baseUri",
                            OllamaEmbeddingProvider.DEFAULT_BASE_URI.toString())),
                    System.getProperty("nexus.semantic.ollama.model", OllamaEmbeddingProvider.DEFAULT_MODEL),
                    Integer.getInteger("nexus.semantic.ollama.dimensions", OllamaEmbeddingProvider.DEFAULT_DIMENSIONS),
                    Long.getLong("nexus.semantic.ollama.timeoutSeconds", OllamaEmbeddingProvider.DEFAULT_TIMEOUT.toSeconds()));
        }

        OllamaEmbeddingProvider provider() {
            return new OllamaEmbeddingProvider(baseUri, model, dimensions, Duration.ofSeconds(timeoutSeconds));
        }
    }

    /** Mesure d'une requête : chemins classés, rang du premier pertinent dans le top {@code K} (0 sinon) et métriques @3. */
    record RetrievalMeasurement(
            List<String> rankedPaths,
            int rankAt3,
            double precisionAt3,
            double recallAt3,
            double hitAt3,
            double mrrAt3,
            long durationMs) {

        static RetrievalMeasurement of(List<String> rankedPaths, Set<String> relevantPaths, long durationMs) {
            List<String> topK = rankedPaths.stream().limit(K).toList();
            long relevantInTopK = topK.stream().filter(relevantPaths::contains).count();
            int rankAt3 = firstRelevantRank(topK, relevantPaths);
            return new RetrievalMeasurement(
                    rankedPaths,
                    rankAt3,
                    (double) relevantInTopK / K,
                    (double) relevantInTopK / relevantPaths.size(),
                    relevantInTopK > 0 ? 1.0d : 0.0d,
                    rankAt3 > 0 ? 1.0d / rankAt3 : 0.0d,
                    durationMs);
        }
    }

    /** Moyennes des métriques @3 et de la durée de recherche sur un ensemble de requêtes. */
    record Aggregate(
            double precisionAt3,
            double recallAt3,
            double hitAt3,
            double mrrAt3,
            double meanSearchMs) {

        static Aggregate of(List<RetrievalMeasurement> measurements) {
            return new Aggregate(
                    mean(measurements.stream().map(RetrievalMeasurement::precisionAt3).toList()),
                    mean(measurements.stream().map(RetrievalMeasurement::recallAt3).toList()),
                    mean(measurements.stream().map(RetrievalMeasurement::hitAt3).toList()),
                    mean(measurements.stream().map(RetrievalMeasurement::mrrAt3).toList()),
                    meanLong(measurements.stream().map(RetrievalMeasurement::durationMs).toList()));
        }
    }

    /** Rang, à partir de 1, du premier chemin pertinent de {@code rankedPaths}, ou 0. */
    static int firstRelevantRank(List<String> rankedPaths, Set<String> relevantPaths) {
        for (int index = 0; index < rankedPaths.size(); index++) {
            if (relevantPaths.contains(rankedPaths.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    /** Résumé d'une configuration mesurée, au format des rapports JSON des benchmarks. */
    static Map<String, Object> measurementSummary(long indexingMs, long semanticIndexBytes, Aggregate aggregate) {
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

    /** Somme des tailles des fichiers réguliers sous {@code root}, 0 si {@code root} n'existe pas. */
    static long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        long[] total = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    total[0] += attributes.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    static boolean isLocalEndpoint(URI baseUri) {
        String host = baseUri.getHost();
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1"));
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    }

    private static double meanLong(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0d);
    }
}
