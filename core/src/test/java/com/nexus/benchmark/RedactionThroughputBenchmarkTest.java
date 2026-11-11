package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.security.SensitiveContentRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mesure le coût des passes de jetons à préfixe de {@link SensitiveContentRedactor} ({@code AUD-PRF-30}).
 *
 * <p>Le banc est opt-in ({@code -Dnexus.scale.benchmark.enabled=true}), comme les autres bancs de ce
 * paquet. Sur deux corpus, il mesure {@code redact}, les neuf passes de {@code STRUCTURED_TOKENS}
 * sans filtre, puis les mêmes passes précédées, comme dans {@code redact}, d'un {@code contains} de
 * leurs préfixes {@code STRUCTURED_TOKEN_PREFIXES}, et vérifie que les deux variantes rendent le même
 * texte. {@code prefilterGainRatio} est le temps que le pré-filtre retire des passes, rapporté au
 * temps de {@code redact}. Le rapport
 * est écrit dans {@code target/redaction-throughput-benchmark.json}
 * ({@code nexus.redaction.benchmark.output}).</p>
 */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class RedactionThroughputBenchmarkTest {

    private static final int ONE_MEGABYTE = 1 << 20;
    private static final int SYNTHETIC_FILES = 2_000;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURED_ROUNDS = 7;

    @Test
    void measuresStructuredTokenPassesAgainstAPrefixFilter() throws Exception {
        List<Pattern> tokens = redactorList("STRUCTURED_TOKENS").stream().map(Pattern.class::cast).toList();
        List<List<String>> prefixes = redactorList("STRUCTURED_TOKEN_PREFIXES").stream()
                .map(family -> ((List<?>) family).stream().map(String.class::cast).toList())
                .toList();
        assertEquals(prefixes.size(), tokens.size());
        Map<String, List<String>> corpora = new LinkedHashMap<>();
        corpora.put("ordinaryCode1MiB", List.of(ordinaryCode()));
        List<String> synthetic = new ArrayList<>(SYNTHETIC_FILES);
        for (int index = 0; index < SYNTHETIC_FILES; index++) {
            synthetic.add(IndexingHeapPeakBenchmarkTest.javaSource(index, SYNTHETIC_FILES));
        }
        corpora.put("indexingHeapSyntheticCorpus", synthetic);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("policyVersion", SensitiveContentRedactor.POLICY_VERSION);
        report.put("measuredRounds", MEASURED_ROUNDS);
        Map<String, Object> results = new LinkedHashMap<>();
        for (var corpus : corpora.entrySet()) {
            results.put(corpus.getKey(), measureCorpus(tokens, prefixes, corpus.getValue()));
        }
        report.put("corpora", results);
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("osName", System.getProperty("os.name"));

        Path output = Path.of(System.getProperty("nexus.redaction.benchmark.output",
                        "target/redaction-throughput-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(output.toFile(), report);
        System.out.printf(Locale.ROOT, "NEXUS redaction throughput benchmark: %s, output=%s%n", results, output);
    }

    private static Map<String, Object> measureCorpus(List<Pattern> tokens, List<List<String>> prefixes,
                                                     List<String> documents) {
        for (String document : documents) {
            assertEquals(tokenPasses(tokens, prefixes, document, false), tokenPasses(tokens, prefixes, document, true));
        }
        long redactNanos = median(() -> {
            for (String document : documents) {
                SensitiveContentRedactor.redact(document);
            }
        });
        long passesNanos = median(() -> {
            for (String document : documents) {
                tokenPasses(tokens, prefixes, document, false);
            }
        });
        long filteredNanos = median(() -> {
            for (String document : documents) {
                tokenPasses(tokens, prefixes, document, true);
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", documents.size());
        result.put("chars", documents.stream().mapToLong(String::length).sum());
        result.put("redactMs", millis(redactNanos));
        result.put("structuredTokenPassesMs", millis(passesNanos));
        result.put("prefilteredTokenPassesMs", millis(filteredNanos));
        result.put("tokenPassesShareOfRedact", (double) passesNanos / Math.max(1L, redactNanos));
        result.put("prefilterGainRatio", (double) (passesNanos - filteredNanos) / Math.max(1L, redactNanos));
        return result;
    }

    private static List<?> redactorList(String name) throws ReflectiveOperationException {
        Field field = SensitiveContentRedactor.class.getDeclaredField(name);
        field.setAccessible(true);
        return (List<?>) field.get(null);
    }

    private static String tokenPasses(List<Pattern> tokens, List<List<String>> prefixes, String content,
                                      boolean prefilter) {
        String redacted = content;
        for (int index = 0; index < tokens.size(); index++) {
            if (prefilter && !containsAny(redacted, prefixes.get(index))) {
                continue;
            }
            redacted = tokens.get(index).matcher(redacted).replaceAll("[REDACTED]");
        }
        return redacted;
    }

    private static boolean containsAny(String content, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (content.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Médiane de {@link #MEASURED_ROUNDS} exécutions après {@link #WARMUP_ROUNDS} tours de chauffe. */
    private static long median(Runnable work) {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            work.run();
        }
        long[] durations = new long[MEASURED_ROUNDS];
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long started = System.nanoTime();
            work.run();
            durations[round] = System.nanoTime() - started;
        }
        Arrays.sort(durations);
        return durations[MEASURED_ROUNDS / 2];
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    /** 1 Mo de code et de configuration ordinaires, sans secret, assemblé à partir d'un motif fixe. */
    private static String ordinaryCode() {
        String block = """
                package com.example.service;

                import java.util.List;
                import java.util.Map;

                /** Service ordinaire : lit une configuration et calcule un total. */
                public final class OrderService {
                    private final Map<String, Integer> quantities;

                    public OrderService(Map<String, Integer> quantities) {
                        this.quantities = Map.copyOf(quantities);
                    }

                    public int total(List<String> skus) {
                        int total = 0;
                        for (String sku : skus) {
                            total += quantities.getOrDefault(sku, 0);
                        }
                        return total; // ghost-free, no shortcut
                    }
                }

                # Configuration
                server.port=8080
                logging.level.root=INFO
                feature.flags=checkout,search,history
                - name: build
                  run: ./mvnw -B verify
                """;
        StringBuilder content = new StringBuilder(ONE_MEGABYTE + block.length());
        while (content.length() < ONE_MEGABYTE) {
            content.append(block);
        }
        return content.toString();
    }
}
