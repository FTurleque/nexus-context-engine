package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileHasher;
import com.nexus.index.IndexWriteSession;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteBatchInsert;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.project.ProjectDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mesure le pic de heap d'un rebuild complet par le pipeline d'indexation réel.
 *
 * <p>Le test est opt-in (mêmes propriétés que {@link ScaleRegressionBenchmarkTest}) et hermétique :
 * il génère un projet Java synthétique, l'enregistre dans un {@code NEXUS_HOME} temporaire puis
 * lance {@code index(projectId, rebuild=true)}. Deux indicateurs de heap sont relevés :</p>
 *
 * <ul>
 *   <li>{@code peakUsedHeapBytes} : heap occupé maximal échantillonné toutes les 10 ms, déchets non
 *       collectés compris ;</li>
 *   <li>{@code peakLiveHeapBytes} : heap occupé maximal juste après une collection forcée par une
 *       sonde périodique ({@code nexus.indexing.heap.benchmark.liveProbeMillis}, 2 000 ms par défaut,
 *       0 pour la désactiver). C'est l'ensemble vivant : ce que le pipeline retient d'un bout à
 *       l'autre, dont les analyses de tous les fichiers modifiés.</li>
 * </ul>
 *
 * <p>La taille du corpus se règle par {@code nexus.scale.benchmark.indexing.files} ; le heap de la
 * JVM de test par {@code -DargLine=-Xmx...}. Le pic vivant sert de ratio, peu sensible à la charge de
 * la machine, contrairement aux durées.</p>
 */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class IndexingHeapPeakBenchmarkTest {

    private static final int METHODS_PER_FILE = 12;
    private static final int IMPORTS_PER_FILE = 4;
    private static final long SAMPLING_PERIOD_MILLIS = 10L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresHeapPeakOfFullRebuild() throws Exception {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci")
                .trim()
                .toLowerCase(Locale.ROOT);
        boolean full = profile.equals("full");
        if (!full && !profile.equals("ci")) {
            throw new IllegalArgumentException("nexus.scale.benchmark.profile must be ci or full");
        }
        int fileCount = Integer.getInteger("nexus.scale.benchmark.indexing.files", full ? 20_000 : 2_000);

        Path root = Files.createDirectory(temporaryDirectory.resolve("heap-corpus"));
        long corpusBytes = createCorpus(root, fileCount);

        try (NexusApplication application = NexusApplication.create(new NexusPaths(temporaryDirectory.resolve("home")))) {
            ProjectDescriptor project = application.registerProject(root, "heap-peak-corpus");

            settleHeap();
            long baselineBytes = usedHeapBytes();
            HeapSampler sampler = new HeapSampler();
            long gcCountBefore = gcCount();
            long gcMillisBefore = gcMillis();
            sampler.start();
            NexusApplication.IndexOperation operation;
            long started = System.nanoTime();
            try {
                operation = application.index(project.id(), true, false);
            } finally {
                sampler.stop();
            }
            long durationMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);

            assertEquals(fileCount, operation.report().scannedFiles());
            assertEquals(fileCount, operation.report().changedFiles());
            assertTrue(operation.report().fullSearchRebuild());

            // Part du double scan (initial puis final) dans la durée totale : un scan complet, puis le
            // hachage seul des mêmes fichiers (cache disque chaud, comme pour le scan final).
            ProjectScanner scanner = new ProjectScanner();
            long scanStarted = System.nanoTime();
            List<ScannedFile> scanned = scanner.scan(root);
            long scanMs = Math.max(0L, (System.nanoTime() - scanStarted) / 1_000_000L);
            long hashStarted = System.nanoTime();
            for (ScannedFile file : scanned) {
                FileHasher.sha256(file.absolutePath(), scanner.maxFileSizeBytes());
            }
            long hashPassMs = Math.max(0L, (System.nanoTime() - hashStarted) / 1_000_000L);

            long symbols = operation.report().statistics().symbols();
            long relations = operation.report().statistics().relations();

            // Durée de la transaction de publication d'une session d'écriture : mêmes volumes mis en
            // attente par lots puis publiés. C'est la durée pendant laquelle l'indexation tient le verrou
            // d'écriture SQLite d'un seul tenant (DATA-0074).
            long writeTransactionMs = measureSingleWriteTransaction(
                    new NexusPaths(temporaryDirectory.resolve("home")), project.id(), scanned, symbols, relations);
            long liveGrowth = Math.max(0L, sampler.peakLive() - baselineBytes);
            long maxHeap = Runtime.getRuntime().maxMemory();

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("generatedAt", Instant.now().toString());
            report.put("profile", profile);
            report.put("files", fileCount);
            report.put("corpusBytes", corpusBytes);
            report.put("symbols", symbols);
            report.put("relations", relations);
            report.put("indexDurationMs", durationMs);
            report.put("scanMs", scanMs);
            report.put("hashPassMs", hashPassMs);
            report.put("hashShareOfScan", ratio(hashPassMs, scanMs));
            report.put("doubleScanShareOfIndexDuration", ratio(2L * scanMs, durationMs));
            report.put("doubleHashShareOfIndexDuration", ratio(2L * hashPassMs, durationMs));
            report.put("singleWriteTransactionMs", writeTransactionMs);
            report.put("writeTransactionShareOfIndexDuration", ratio(writeTransactionMs, durationMs));
            report.put("samples", sampler.samples());
            report.put("liveProbes", sampler.liveProbes());
            report.put("baselineUsedHeapBytes", baselineBytes);
            report.put("peakUsedHeapBytes", sampler.peakUsed());
            report.put("peakLiveHeapBytes", sampler.peakLive());
            report.put("liveGrowthOverBaselineBytes", liveGrowth);
            report.put("liveGrowthBytesPerFile", fileCount == 0 ? 0L : liveGrowth / fileCount);
            report.put("peakUsedHeapRatioOfMax", ratio(sampler.peakUsed(), maxHeap));
            report.put("peakLiveHeapRatioOfMax", ratio(sampler.peakLive(), maxHeap));
            report.put("gcCollections", gcCount() - gcCountBefore);
            report.put("gcTimeMs", gcMillis() - gcMillisBefore);
            report.put("maxHeapBytes", maxHeap);
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("osName", System.getProperty("os.name"));

            Path output = Path.of(System.getProperty(
                            "nexus.indexing.heap.benchmark.output",
                            "target/indexing-heap-benchmark.json"))
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(output.getParent());
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(output.toFile(), report);

            System.out.printf(
                    Locale.ROOT,
                    "NEXUS indexing heap benchmark: profile=%s, files=%d, symbols=%d, relations=%d, "
                            + "peakUsed=%d, peakLive=%d, baseline=%d, maxHeap=%d, duration=%dms, output=%s%n",
                    profile,
                    fileCount,
                    symbols,
                    relations,
                    sampler.peakUsed(),
                    sampler.peakLive(),
                    baselineBytes,
                    maxHeap,
                    durationMs,
                    output);
        }
    }

    private static long measureSingleWriteTransaction(
            NexusPaths paths,
            java.util.UUID projectId,
            List<ScannedFile> scanned,
            long symbols,
            long relations) throws Exception {
        int files = Math.max(1, scanned.size());
        int symbolsPerFile = (int) Math.max(1L, symbols / files);
        int relationsPerFile = (int) Math.max(0L, relations / files);
        List<IndexedFileUpdate> updates = new ArrayList<>(scanned.size());
        for (ScannedFile file : scanned) {
            List<CodeSymbol> fileSymbols = new ArrayList<>(symbolsPerFile);
            for (int symbol = 0; symbol < symbolsPerFile; symbol++) {
                String name = "writeSymbol" + symbol;
                fileSymbols.add(new CodeSymbol(
                        SymbolKind.METHOD,
                        name,
                        file.relativePath().replace('/', '.') + "." + name,
                        "String " + name + "(String input)",
                        symbol + 1,
                        symbol + 1));
            }
            List<SymbolRelation> fileRelations = new ArrayList<>(relationsPerFile);
            for (int relation = 0; relation < relationsPerFile; relation++) {
                fileRelations.add(new SymbolRelation(
                        RelationKind.IMPORTS,
                        file.relativePath(),
                        "bench.write.Target" + relation));
            }
            updates.add(new IndexedFileUpdate(
                    file,
                    new AnalysisResult(file.absolutePath(), file.language(), fileSymbols, fileRelations)));
        }
        SqliteIndexRepository repository = new SqliteIndexRepository(new SqliteDatabase(paths));
        try (IndexWriteSession session = repository.openWriteSession(projectId)) {
            for (int from = 0; from < updates.size(); from += SqliteBatchInsert.ROW_LIMIT) {
                session.stage(updates.subList(from, Math.min(updates.size(), from + SqliteBatchInsert.ROW_LIMIT)));
            }
            long started = System.nanoTime();
            session.publish(java.util.Set.of());
            return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private static long createCorpus(Path root, int fileCount) throws IOException {
        long bytes = 0L;
        for (int index = 0; index < fileCount; index++) {
            Path directory = root.resolve("src/main/java/bench/p" + (index % 200));
            Files.createDirectories(directory);
            String source = javaSource(index, fileCount);
            Files.writeString(directory.resolve("HeapService" + index + ".java"), source);
            bytes += source.length();
        }
        return bytes;
    }

    private static String javaSource(int index, int fileCount) {
        StringBuilder source = new StringBuilder(2_048);
        source.append("package bench.p").append(index % 200).append(";\n\n");
        for (int target = 1; target <= IMPORTS_PER_FILE; target++) {
            int other = (index + target * 7) % fileCount;
            source.append("import bench.p").append(other % 200).append(".HeapService").append(other).append(";\n");
        }
        source.append('\n');
        source.append("public final class HeapService").append(index).append(" {\n");
        source.append("    private final java.util.List<String> values = new java.util.ArrayList<>();\n\n");
        for (int method = 0; method < METHODS_PER_FILE; method++) {
            source.append("    public String operation").append(method).append("(String input) {\n");
            source.append("        values.add(input);\n");
            source.append("        return ").append(method == 0 ? "input" : "operation" + (method - 1) + "(input)")
                    .append(" + \"-heap-").append(index).append('-').append(method).append("\";\n");
            source.append("    }\n\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private static double ratio(long value, long maximum) {
        return maximum <= 0L ? 0.0d : (double) value / (double) maximum;
    }

    private static void settleHeap() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(100L);
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value >= 0L)
                .sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .filter(value -> value >= 0L)
                .sum();
    }

    /** Échantillonne le heap occupé et, périodiquement, l'ensemble vivant après collection forcée. */
    private static final class HeapSampler {
        private final long liveProbeMillis = Long.getLong("nexus.indexing.heap.benchmark.liveProbeMillis", 2_000L);
        private final Thread sampling = new Thread(this::runSampling, "nexus-heap-sampler");
        private final Thread probing = new Thread(this::runProbing, "nexus-heap-live-probe");
        private volatile boolean running;
        private volatile long peakUsed;
        private volatile long peakLive;
        private volatile long samples;
        private volatile long liveProbes;

        private HeapSampler() {
            sampling.setDaemon(true);
            probing.setDaemon(true);
        }

        private void start() {
            running = true;
            sampling.start();
            if (liveProbeMillis > 0L) {
                probing.start();
            }
        }

        private void stop() throws InterruptedException {
            running = false;
            sampling.join(5_000L);
            if (probing.isAlive()) {
                probing.interrupt();
                probing.join(30_000L);
            }
            sample();
        }

        private void runSampling() {
            while (running) {
                sample();
                try {
                    Thread.sleep(SAMPLING_PERIOD_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void runProbing() {
            while (running) {
                try {
                    Thread.sleep(liveProbeMillis);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (!running) {
                    return;
                }
                System.gc();
                long live = usedHeapBytes();
                if (live > peakLive) {
                    peakLive = live;
                }
                liveProbes++;
            }
        }

        private void sample() {
            long used = usedHeapBytes();
            if (used > peakUsed) {
                peakUsed = used;
            }
            samples++;
        }

        private long peakUsed() {
            return peakUsed;
        }

        private long peakLive() {
            return peakLive;
        }

        private long samples() {
            return samples;
        }

        private long liveProbes() {
            return liveProbes;
        }
    }
}
