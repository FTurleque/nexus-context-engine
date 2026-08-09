package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.config.NexusPaths;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import com.nexus.search.SearchSignals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual graph enrichment path on a large persistent index. */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class GraphScaleRegressionBenchmarkTest {

    private static final int BATCH_SIZE = 5_000;
    private static final int SYMBOLS_PER_FILE = 100;
    private static final int SEED_COUNT = 20;
    private static final int SAMPLES = 3;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresBoundedGraphEnrichmentAtScale() throws Exception {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci").trim().toLowerCase(Locale.ROOT);
        boolean full = profile.equals("full");
        if (!full && !profile.equals("ci")) {
            throw new IllegalArgumentException("nexus.scale.benchmark.profile must be ci or full");
        }

        int symbolCount = full ? 1_000_000 : 100_000;
        int relationCount = full ? 1_000_000 : 100_000;
        int fileCount = Math.max(1, symbolCount / SYMBOLS_PER_FILE);

        Path home = temporaryDirectory.resolve("graph-scale-home");
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(home));
        UUID projectId = UUID.randomUUID();

        long populateStarted = System.nanoTime();
        populateGraphProject(database, projectId, fileCount, symbolCount, relationCount);
        long populationMs = elapsedMillis(populateStarted);

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        GraphCandidateEnricher enricher = new GraphCandidateEnricher(repository);
        Path projectRoot = temporaryDirectory.resolve("synthetic-project").toAbsolutePath().normalize();
        ProjectDescriptor project = new ProjectDescriptor(
                projectId,
                "graph-scale",
                projectRoot,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                Instant.now(),
                IndexStatus.READY);

        List<SearchCandidate> seeds = new ArrayList<>();
        for (int index = 0; index < Math.min(SEED_COUNT, fileCount); index++) {
            String relativePath = filePath(index);
            seeds.add(new SearchCandidate(
                    "file:" + relativePath,
                    CandidateType.FILE,
                    projectRoot.resolve(relativePath),
                    null,
                    relativePath,
                    Map.of(SearchSignals.LEXICAL, 1.0d)));
        }

        enricher.enrich(project, seeds);
        System.gc();
        long heapBefore = usedHeapBytes();
        List<Long> samplesMicros = new ArrayList<>();
        List<SearchCandidate> representative = List.of();
        for (int sample = 0; sample < SAMPLES; sample++) {
            long started = System.nanoTime();
            representative = enricher.enrich(project, seeds);
            samplesMicros.add((System.nanoTime() - started) / 1_000L);
        }
        long heapAfter = usedHeapBytes();

        long graphCandidates = representative.stream()
                .filter(candidate -> candidate.signals().containsKey(SearchSignals.GRAPH))
                .count();
        assertFalse(representative.isEmpty());
        assertTrue(graphCandidates > 0, "large graph benchmark must exercise graph-derived candidates");
        assertTrue(graphCandidates <= 4_000, "two bounded hops must not materialize an unbounded graph");

        samplesMicros.sort(Comparator.naturalOrder());
        double p95Ms = percentileMillis(samplesMicros, 0.95d);
        long heapDelta = Math.max(0L, heapAfter - heapBefore);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("profile", profile);
        report.put("symbols", symbolCount);
        report.put("relations", relationCount);
        report.put("files", fileCount);
        report.put("seedFiles", seeds.size());
        report.put("graphCandidates", graphCandidates);
        report.put("samples", samplesMicros.size());
        report.put("p95Ms", p95Ms);
        report.put("populationMs", populationMs);
        report.put("heapDeltaBytes", heapDelta);
        report.put("databaseBytes", Files.size(database.databaseFile()));

        Path output = Path.of(System.getProperty(
                        "nexus.graph.scale.benchmark.output",
                        "target/graph-scale-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS graph scale benchmark: profile=%s symbols=%d relations=%d candidates=%d p95=%.2fms heapDelta=%d output=%s%n",
                profile,
                symbolCount,
                relationCount,
                graphCandidates,
                p95Ms,
                heapDelta,
                output);
    }

    private static void populateGraphProject(
            SqliteDatabase database,
            UUID projectId,
            int fileCount,
            int symbolCount,
            int relationCount) throws Exception {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement project = connection.prepareStatement("""
                        INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                        VALUES (?, 'graph-scale', ?, 'LOCAL', NULL, 'READY')
                        """)) {
                    project.setString(1, projectId.toString());
                    project.setString(2, "/graph-scale/" + projectId);
                    project.executeUpdate();
                }

                try (PreparedStatement files = connection.prepareStatement("""
                        INSERT INTO indexed_files(
                            id, project_id, relative_path, language, size_bytes,
                            content_hash, modified_at, estimated_tokens, category)
                        VALUES (?, ?, ?, 'java', 1, ?, '2026-08-08T00:00:00Z', 1, 'SOURCE')
                        """)) {
                    for (int index = 0; index < fileCount; index++) {
                        files.setLong(1, index + 1L);
                        files.setString(2, projectId.toString());
                        files.setString(3, filePath(index));
                        files.setString(4, "hash-" + index);
                        files.addBatch();
                        executeBatch(files, index + 1);
                    }
                    files.executeBatch();
                }

                try (PreparedStatement symbols = connection.prepareStatement("""
                        INSERT INTO symbols(
                            file_id, kind, name, qualified_name, signature,
                            start_line, end_line, source_provider)
                        VALUES (?, ?, ?, ?, ?, 1, 2, 'embedded')
                        """)) {
                    for (int index = 0; index < symbolCount; index++) {
                        int fileIndex = index % fileCount;
                        boolean type = index < fileCount;
                        String typeName = typeName(fileIndex);
                        symbols.setLong(1, fileIndex + 1L);
                        symbols.setString(2, type ? "CLASS" : "METHOD");
                        symbols.setString(3, type ? simpleTypeName(fileIndex) : "method" + index);
                        symbols.setString(4, type ? typeName : typeName + ".method" + index);
                        symbols.setString(5, type ? "class " + simpleTypeName(fileIndex) : "void method" + index + "()");
                        symbols.addBatch();
                        executeBatch(symbols, index + 1);
                    }
                    symbols.executeBatch();
                }

                try (PreparedStatement relations = connection.prepareStatement("""
                        INSERT INTO symbol_relations(
                            project_id, file_id, kind, source_ref, target_ref, confidence, source_provider)
                        VALUES (?, ?, 'IMPORTS', ?, ?, 1.0, 'embedded')
                        """)) {
                    for (int index = 0; index < relationCount; index++) {
                        int sourceIndex = index % fileCount;
                        int offset = 1 + (index / fileCount) % Math.min(100, Math.max(1, fileCount - 1));
                        int targetIndex = (sourceIndex + offset) % fileCount;
                        relations.setString(1, projectId.toString());
                        relations.setLong(2, sourceIndex + 1L);
                        relations.setString(3, filePath(sourceIndex));
                        relations.setString(4, typeName(targetIndex));
                        relations.addBatch();
                        executeBatch(relations, index + 1);
                    }
                    relations.executeBatch();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void executeBatch(PreparedStatement statement, int count) throws Exception {
        if (count % BATCH_SIZE == 0) {
            statement.executeBatch();
        }
    }

    private static String filePath(int index) {
        return String.format(Locale.ROOT, "src/generated/File%05d.java", index);
    }

    private static String simpleTypeName(int index) {
        return String.format(Locale.ROOT, "Type%05d", index);
    }

    private static String typeName(int index) {
        return "bench." + simpleTypeName(index);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static double percentileMillis(List<Long> sortedMicros, double percentile) {
        int index = Math.min(
                sortedMicros.size() - 1,
                Math.max(0, (int) Math.ceil(sortedMicros.size() * percentile) - 1));
        return sortedMicros.get(index) / 1_000.0d;
    }
}
