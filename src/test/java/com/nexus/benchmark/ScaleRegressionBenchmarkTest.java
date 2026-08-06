package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.SearchDocument;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticIndexingService;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmark synthétique hermétique pour les limites connues de passage à l'échelle.
 *
 * <p>Le test est opt-in afin de ne pas ralentir le build standard. Il construit
 * ses propres corpus et n'accède à aucun repository ni service externe.</p>
 */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class ScaleRegressionBenchmarkTest {

    private static final int BATCH_SIZE = 5_000;
    private static final int SYMBOLS_PER_FILE = 100;
    private static final int QUERY_WARMUPS = 2;
    private static final int QUERY_SAMPLES = 5;
    private static final int SEARCH_LIMIT = 20;
    private static final int PORTFOLIO_FILES_PER_PROJECT = 2;
    private static final int PORTFOLIO_CONTEXT_BUDGET = 2_400;
    private static final int SEMANTIC_DIMENSIONS = 32;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresHermeticScaleProfile() throws Exception {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci").trim().toLowerCase(Locale.ROOT);
        boolean full = profile.equals("full");
        if (!full && !profile.equals("ci")) {
            throw new IllegalArgumentException("nexus.scale.benchmark.profile must be ci or full");
        }

        List<Integer> sqliteTiers = full
                ? List.of(10_000, 100_000, 500_000, 1_000_000)
                : List.of(10_000, 100_000);
        List<Integer> portfolioTiers = full
                ? List.of(10, 25, 50, 100)
                : List.of(10, 25);
        int concurrencySymbolsPerProject = full ? 100_000 : 25_000;
        int semanticDocuments = full ? 20_000 : 5_000;

        long benchmarkStarted = System.nanoTime();
        long usedHeapBefore = usedHeapBytes();

        List<Map<String, Object>> sqlite = new ArrayList<>();
        for (int symbolCount : sqliteTiers) {
            sqlite.add(benchmarkSqliteTier(symbolCount));
        }

        Map<String, Object> portfolio = benchmarkPortfolio(portfolioTiers);
        Map<String, Object> concurrency = benchmarkJournalModes(concurrencySymbolsPerProject);
        Map<String, Object> semantic = benchmarkSemanticRecovery(semanticDocuments);

        long usedHeapAfter = usedHeapBytes();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("profile", profile);
        report.put("environment", environment());
        report.put("protocol", Map.of(
                "queryWarmups", QUERY_WARMUPS,
                "querySamples", QUERY_SAMPLES,
                "searchLimit", SEARCH_LIMIT,
                "symbolsPerFile", SYMBOLS_PER_FILE,
                "portfolioFilesPerProject", PORTFOLIO_FILES_PER_PROJECT,
                "portfolioContextBudget", PORTFOLIO_CONTEXT_BUDGET,
                "semanticDimensions", SEMANTIC_DIMENSIONS));
        report.put("sqliteTiers", sqlite);
        report.put("portfolio", portfolio);
        report.put("concurrentReadWrite", concurrency);
        report.put("semanticRecovery", semantic);
        report.put("usedHeapBeforeBytes", usedHeapBefore);
        report.put("usedHeapAfterBytes", usedHeapAfter);
        report.put("usedHeapDeltaBytes", usedHeapAfter - usedHeapBefore);
        report.put("totalDurationMs", elapsedMillis(benchmarkStarted));

        Path output = Path.of(System.getProperty(
                        "nexus.scale.benchmark.output",
                        "target/scale-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS scale benchmark: profile=%s, sqliteMax=%d, portfolioMax=%d, semanticDocs=%d, duration=%dms, output=%s%n",
                profile,
                sqliteTiers.getLast(),
                portfolioTiers.getLast(),
                semanticDocuments,
                elapsedMillis(benchmarkStarted),
                output);
    }

    private Map<String, Object> benchmarkSqliteTier(int symbolCount) throws Exception {
        Path home = temporaryDirectory.resolve("sqlite-" + symbolCount);
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(home));
        String journalMode = setJournalMode(database, "DELETE");
        UUID projectId = UUID.randomUUID();

        long populateStarted = System.nanoTime();
        populateProject(database, projectId, "sqlite-" + symbolCount, symbolCount, symbolCount);
        long populationMs = elapsedMillis(populateStarted);

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        Measurement exact = measure(() -> repository.searchSymbols(projectId, "BenchSymbol00000010", SEARCH_LIMIT));
        Measurement contains = measure(() -> repository.searchSymbols(projectId, "ScaleNeedle", SEARCH_LIMIT));
        Measurement missing = measure(() -> repository.searchSymbols(projectId, "DefinitelyAbsentScaleToken", SEARCH_LIMIT));
        Measurement relations = measure(() -> repository.searchRelations(projectId, "TargetNeedle", SEARCH_LIMIT));

        int fileCount = Math.max(1, (symbolCount + SYMBOLS_PER_FILE - 1) / SYMBOLS_PER_FILE);
        Set<String> targetedPaths = java.util.stream.IntStream.range(0, Math.min(100, fileCount))
                .mapToObj(index -> filePath(index))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Measurement targetedFiles = measure(() -> repository.findFiles(projectId, targetedPaths));

        assertFalse(repository.searchSymbols(projectId, "ScaleNeedle", SEARCH_LIMIT).isEmpty());
        assertFalse(repository.searchRelations(projectId, "TargetNeedle", SEARCH_LIMIT).isEmpty());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbols", symbolCount);
        result.put("relations", symbolCount);
        result.put("files", fileCount);
        result.put("journalMode", journalMode);
        result.put("populationMs", populationMs);
        result.put("databaseBytes", sqliteFilesSize(database.databaseFile()));
        result.put("symbolExact", exact.asMap());
        result.put("symbolContains", contains.asMap());
        result.put("symbolMissingWorstCase", missing.asMap());
        result.put("relationContains", relations.asMap());
        result.put("targeted100Files", targetedFiles.asMap());
        return result;
    }

    private Map<String, Object> benchmarkPortfolio(List<Integer> tiers) throws Exception {
        Path home = temporaryDirectory.resolve("portfolio-home");
        NexusApplication application = NexusApplication.create(new NexusPaths(home));
        int maximumProjects = tiers.getLast();
        List<ProjectDescriptor> projects = new ArrayList<>(maximumProjects);
        long totalIndexMs = 0L;

        for (int projectIndex = 0; projectIndex < maximumProjects; projectIndex++) {
            Path root = createPortfolioProject(projectIndex);
            ProjectDescriptor project = application.registerProject(root, "scale-project-" + projectIndex);
            totalIndexMs += application.index(project.id(), true, false).report().duration().toMillis();
            projects.add(project);
        }

        List<Map<String, Object>> tierMetrics = new ArrayList<>();
        for (int tier : tiers) {
            List<UUID> ids = projects.stream().limit(tier).map(ProjectDescriptor::id).toList();
            Measurement search = measure(() -> application.searchAcrossProjects(ids, "SharedScaleNeedle", SEARCH_LIMIT, false));
            Measurement context = measureContext(() -> application.contextAcrossProjects(
                    ids,
                    "SharedScaleNeedle",
                    PORTFOLIO_CONTEXT_BUDGET,
                    Set.of(),
                    Map.of(),
                    false));

            NexusApplication.FederatedSearchOperation representative =
                    application.searchAcrossProjects(ids, "SharedScaleNeedle", SEARCH_LIMIT, false);
            assertFalse(representative.results().isEmpty());

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("projects", tier);
            metrics.put("files", tier * PORTFOLIO_FILES_PER_PROJECT);
            metrics.put("search", search.asMap());
            metrics.put("context", context.asMap());
            metrics.put("representativeResults", representative.results().size());
            metrics.put("projectsRepresented", representative.results().stream()
                    .map(hit -> hit.project().id())
                    .distinct()
                    .count());
            tierMetrics.add(metrics);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maximumProjects", maximumProjects);
        result.put("totalFullIndexMs", totalIndexMs);
        result.put("tiers", tierMetrics);
        return result;
    }

    private Map<String, Object> benchmarkJournalModes(int symbolsPerProject) throws Exception {
        Map<String, Object> delete = benchmarkConcurrentReadWrite("DELETE", symbolsPerProject);
        Map<String, Object> wal = benchmarkConcurrentReadWrite("WAL", symbolsPerProject);

        double deleteP95 = ((Number) ((Map<?, ?>) delete.get("reader")).get("p95Ms")).doubleValue();
        double walP95 = ((Number) ((Map<?, ?>) wal.get("reader")).get("p95Ms")).doubleValue();
        double improvement = deleteP95 <= 0.0d ? 0.0d : (deleteP95 - walP95) / deleteP95;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbolsPerProject", symbolsPerProject);
        result.put("delete", delete);
        result.put("wal", wal);
        result.put("readerP95ImprovementRatio", improvement);
        result.put("adoptionRule", "Adopt WAL only after repeated runs show >=25% reader p95 improvement without writer or recovery regression");
        return result;
    }

    private Map<String, Object> benchmarkConcurrentReadWrite(String requestedJournalMode, int symbolsPerProject)
            throws Exception {
        Path home = temporaryDirectory.resolve("concurrency-" + requestedJournalMode.toLowerCase(Locale.ROOT));
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(home));
        String actualJournalMode = setJournalMode(database, requestedJournalMode);
        UUID writerProject = UUID.randomUUID();
        UUID readerProject = UUID.randomUUID();
        populateProject(database, writerProject, "writer", symbolsPerProject, 0);
        populateProject(database, readerProject, "reader", symbolsPerProject, 0);

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writerDone = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> writer = executor.submit(() -> {
                start.await();
                long started = System.nanoTime();
                for (int iteration = 0; iteration < 40; iteration++) {
                    try (Connection connection = database.openConnection()) {
                        connection.setAutoCommit(false);
                        try (PreparedStatement statement = connection.prepareStatement("""
                                UPDATE indexed_files
                                SET estimated_tokens = estimated_tokens + 1
                                WHERE project_id = ?
                                  AND id IN (
                                      SELECT id FROM indexed_files
                                      WHERE project_id = ?
                                      ORDER BY id
                                      LIMIT 100
                                  )
                                """)) {
                            statement.setString(1, writerProject.toString());
                            statement.setString(2, writerProject.toString());
                            statement.executeUpdate();
                            connection.commit();
                        }
                    }
                }
                writerDone.set(true);
                return elapsedMillis(started);
            });

            Future<ConcurrentReads> reader = executor.submit(() -> {
                start.await();
                List<Long> durations = new ArrayList<>();
                int failures = 0;
                do {
                    long started = System.nanoTime();
                    try {
                        repository.searchSymbols(readerProject, "DefinitelyAbsentConcurrentToken", SEARCH_LIMIT);
                        durations.add(elapsedMicros(started));
                    } catch (RuntimeException failure) {
                        failures++;
                    }
                } while (!writerDone.get() || durations.size() < 20);
                return new ConcurrentReads(durations, failures);
            });

            start.countDown();
            long writerMs = writer.get();
            ConcurrentReads reads = reader.get();
            assertTrue(reads.failures() == 0, "Concurrent SQLite reads must not fail in " + requestedJournalMode);

            Map<String, Object> readerMetrics = measurementFromMicros(reads.durationsMicros());
            readerMetrics.put("samples", reads.durationsMicros().size());
            readerMetrics.put("failures", reads.failures());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("journalMode", actualJournalMode);
            result.put("writerTransactions", 40);
            result.put("writerTotalMs", writerMs);
            result.put("reader", readerMetrics);
            result.put("databaseBytes", sqliteFilesSize(database.databaseFile()));
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> benchmarkSemanticRecovery(int documentCount) throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        DeterministicEmbeddingProvider provider = new DeterministicEmbeddingProvider(SEMANTIC_DIMENSIONS);
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, SEMANTIC_DIMENSIONS);
        SemanticIndexingService service = new SemanticIndexingService(provider, index, 2_000, 128);
        UUID projectId = UUID.randomUUID();
        List<SearchDocument> documents = new ArrayList<>(documentCount);
        for (int indexNumber = 0; indexNumber < documentCount; indexNumber++) {
            documents.add(new SearchDocument(
                    "src/generated/Doc" + indexNumber + ".java",
                    "java",
                    FileCategory.SOURCE,
                    "synthetic semantic scale document " + indexNumber + " shared recovery context",
                    List.of()));
        }

        String firstFingerprint = "synthetic-semantic-fingerprint-v1";
        long firstStarted = System.nanoTime();
        service.rebuild(projectId, firstFingerprint, documents);
        long firstMs = elapsedMillis(firstStarted);
        assertTrue(service.isCompatible(projectId, firstFingerprint));

        String secondFingerprint = "synthetic-semantic-fingerprint-v2";
        assertFalse(service.isCompatible(projectId, secondFingerprint));
        long recoveryStarted = System.nanoTime();
        service.rebuild(projectId, secondFingerprint, documents);
        long recoveryMs = elapsedMillis(recoveryStarted);
        assertTrue(service.isCompatible(projectId, secondFingerprint));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", documentCount);
        result.put("dimensions", SEMANTIC_DIMENSIONS);
        result.put("batchSize", 128);
        result.put("initialRebuildMs", firstMs);
        result.put("incompatibleRecoveryRebuildMs", recoveryMs);
        result.put("semanticIndexBytes", directorySize(paths.projectSemanticLuceneIndex(projectId)));
        result.put("vectorsProduced", provider.vectorsProduced());
        return result;
    }

    private void populateProject(
            SqliteDatabase database,
            UUID projectId,
            String projectName,
            int symbolCount,
            int relationCount) throws SQLException {
        int fileCount = Math.max(1, (symbolCount + SYMBOLS_PER_FILE - 1) / SYMBOLS_PER_FILE);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement project = connection.prepareStatement("""
                        INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                        VALUES (?, ?, ?, 'LOCAL', ?, 'READY')
                        """)) {
                    project.setString(1, projectId.toString());
                    project.setString(2, projectName + "-" + projectId);
                    project.setString(3, temporaryDirectory.resolve("db-project-" + projectId).toString());
                    project.setString(4, Instant.now().toString());
                    project.executeUpdate();
                }
                try (PreparedStatement generation = connection.prepareStatement("""
                        INSERT INTO project_index_generations(project_id, generation) VALUES (?, 1)
                        """)) {
                    generation.setString(1, projectId.toString());
                    generation.executeUpdate();
                }

                try (PreparedStatement files = connection.prepareStatement("""
                        INSERT INTO indexed_files(
                            project_id, relative_path, language, size_bytes,
                            content_hash, modified_at, estimated_tokens, category)
                        VALUES (?, ?, 'java', 128, ?, ?, 32, 'SOURCE')
                        """)) {
                    for (int file = 0; file < fileCount; file++) {
                        files.setString(1, projectId.toString());
                        files.setString(2, filePath(file));
                        files.setString(3, "hash-" + projectId + "-" + file);
                        files.setString(4, Instant.EPOCH.plusSeconds(file).toString());
                        files.addBatch();
                        if ((file + 1) % BATCH_SIZE == 0) {
                            files.executeBatch();
                        }
                    }
                    files.executeBatch();
                }
                connection.commit();

                List<Long> fileIds = new ArrayList<>(fileCount);
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT id FROM indexed_files WHERE project_id = ? ORDER BY id
                        """)) {
                    select.setString(1, projectId.toString());
                    try (ResultSet resultSet = select.executeQuery()) {
                        while (resultSet.next()) {
                            fileIds.add(resultSet.getLong(1));
                        }
                    }
                }

                try (PreparedStatement symbols = connection.prepareStatement("""
                        INSERT INTO symbols(
                            file_id, kind, name, qualified_name, signature,
                            start_line, end_line, source_provider)
                        VALUES (?, 'CLASS', ?, ?, ?, 1, 2, 'javaparser')
                        """)) {
                    for (int symbol = 0; symbol < symbolCount; symbol++) {
                        long fileId = fileIds.get(symbol % fileIds.size());
                        String name = symbol % 10_000 == 0
                                ? String.format(Locale.ROOT, "ScaleNeedle%08d", symbol)
                                : String.format(Locale.ROOT, "BenchSymbol%08d", symbol);
                        String qualified = "bench.generated." + name;
                        symbols.setLong(1, fileId);
                        symbols.setString(2, name);
                        symbols.setString(3, qualified);
                        symbols.setString(4, "class " + name);
                        symbols.addBatch();
                        if ((symbol + 1) % BATCH_SIZE == 0) {
                            symbols.executeBatch();
                        }
                        if ((symbol + 1) % 50_000 == 0) {
                            symbols.executeBatch();
                            connection.commit();
                        }
                    }
                    symbols.executeBatch();
                    connection.commit();
                }

                if (relationCount > 0) {
                    try (PreparedStatement relations = connection.prepareStatement("""
                            INSERT INTO symbol_relations(
                                project_id, file_id, kind, source_ref, target_ref,
                                confidence, source_provider)
                            VALUES (?, ?, 'USES', ?, ?, 1.0, 'javaparser')
                            """)) {
                        for (int relation = 0; relation < relationCount; relation++) {
                            long fileId = fileIds.get(relation % fileIds.size());
                            String source = "bench.generated.BenchSymbol" + String.format(Locale.ROOT, "%08d", relation);
                            String target = relation % 10_000 == 0
                                    ? "bench.target.TargetNeedle" + relation
                                    : "bench.target.Target" + relation;
                            relations.setString(1, projectId.toString());
                            relations.setLong(2, fileId);
                            relations.setString(3, source);
                            relations.setString(4, target);
                            relations.addBatch();
                            if ((relation + 1) % BATCH_SIZE == 0) {
                                relations.executeBatch();
                            }
                            if ((relation + 1) % 50_000 == 0) {
                                relations.executeBatch();
                                connection.commit();
                            }
                        }
                        relations.executeBatch();
                        connection.commit();
                    }
                }
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private Path createPortfolioProject(int projectIndex) throws IOException {
        Path root = temporaryDirectory.resolve("portfolio-project-" + projectIndex);
        Path javaFile = root.resolve("src/main/java/bench/p" + projectIndex + "/SharedScaleNeedleService.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package bench.p%d;

                public final class SharedScaleNeedleService {
                    public String sharedScaleNeedle() {
                        return "shared-scale-needle-%d";
                    }
                }
                """.formatted(projectIndex, projectIndex));
        Path markdown = root.resolve("docs/scale.md");
        Files.createDirectories(markdown.getParent());
        Files.writeString(markdown,
                "Project " + projectIndex + " documents SharedScaleNeedle federation and context assembly.");
        return root;
    }

    private static Measurement measure(ThrowingSupplier<?> operation) throws Exception {
        for (int warmup = 0; warmup < QUERY_WARMUPS; warmup++) {
            operation.get();
        }
        List<Long> micros = new ArrayList<>();
        for (int sample = 0; sample < QUERY_SAMPLES; sample++) {
            long started = System.nanoTime();
            operation.get();
            micros.add(elapsedMicros(started));
        }
        return Measurement.fromMicros(micros);
    }

    private static Measurement measureContext(ThrowingSupplier<?> operation) throws Exception {
        operation.get();
        List<Long> micros = new ArrayList<>();
        for (int sample = 0; sample < 3; sample++) {
            long started = System.nanoTime();
            operation.get();
            micros.add(elapsedMicros(started));
        }
        return Measurement.fromMicros(micros);
    }

    private static String setJournalMode(SqliteDatabase database, String requested) throws SQLException {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode=" + requested)) {
            return resultSet.next() ? resultSet.getString(1).toUpperCase(Locale.ROOT) : requested;
        }
    }

    private static long sqliteFilesSize(Path databaseFile) throws IOException {
        Path parent = databaseFile.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return Files.exists(databaseFile) ? Files.size(databaseFile) : 0L;
        }
        String prefix = databaseFile.getFileName().toString();
        try (var stream = Files.list(parent)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new DirectorySizeFailure(exception);
                        }
                    })
                    .sum();
        } catch (DirectorySizeFailure failure) {
            throw failure.ioException();
        }
    }

    private static long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            throw new DirectorySizeFailure(exception);
                        }
                    })
                    .sum();
        } catch (DirectorySizeFailure failure) {
            throw failure.ioException();
        }
    }

    private static Map<String, Object> environment() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("osName", System.getProperty("os.name"));
        result.put("osArch", System.getProperty("os.arch"));
        result.put("availableProcessors", runtime.availableProcessors());
        result.put("maxHeapBytes", runtime.maxMemory());
        return result;
    }

    private static Map<String, Object> measurementFromMicros(List<Long> micros) {
        Measurement measurement = Measurement.fromMicros(micros);
        return new LinkedHashMap<>(measurement.asMap());
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private static long elapsedMicros(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000L);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String filePath(int fileIndex) {
        return String.format(Locale.ROOT, "src/generated/File%08d.java", fileIndex);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Measurement(long p50Micros, long p95Micros, long maxMicros, long meanMicros) {
        private static Measurement fromMicros(List<Long> micros) {
            long mean = micros.isEmpty()
                    ? 0L
                    : Math.round(micros.stream().mapToLong(Long::longValue).average().orElse(0.0d));
            return new Measurement(
                    percentile(micros, 0.50d),
                    percentile(micros, 0.95d),
                    micros.stream().mapToLong(Long::longValue).max().orElse(0L),
                    mean);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("p50Micros", p50Micros);
            result.put("p95Micros", p95Micros);
            result.put("p50Ms", p50Micros / 1_000.0d);
            result.put("p95Ms", p95Micros / 1_000.0d);
            result.put("maxMs", maxMicros / 1_000.0d);
            result.put("meanMs", meanMicros / 1_000.0d);
            return result;
        }
    }

    private record ConcurrentReads(List<Long> durationsMicros, int failures) {
    }

    private static final class DeterministicEmbeddingProvider implements EmbeddingProvider {
        private final int dimensions;
        private long vectorsProduced;

        private DeterministicEmbeddingProvider(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public String providerId() {
            return "synthetic-scale-provider";
        }

        @Override
        public String modelId() {
            return "synthetic-scale-model-v1";
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = new float[dimensions];
            int seed = text.hashCode();
            double norm = 0.0d;
            for (int index = 0; index < dimensions; index++) {
                seed = 31 * seed + index * 17 + 7;
                float value = ((seed >>> 8) & 0xFFFF) / 32768.0f - 1.0f;
                vector[index] = value;
                norm += value * value;
            }
            double divisor = Math.sqrt(Math.max(norm, 1.0e-12d));
            for (int index = 0; index < dimensions; index++) {
                vector[index] = (float) (vector[index] / divisor);
            }
            vectorsProduced++;
            return vector;
        }

        private long vectorsProduced() {
            return vectorsProduced;
        }
    }

    private static final class DirectorySizeFailure extends RuntimeException {
        private final IOException ioException;

        private DirectorySizeFailure(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}
