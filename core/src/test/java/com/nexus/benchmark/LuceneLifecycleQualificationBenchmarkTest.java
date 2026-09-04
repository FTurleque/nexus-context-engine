package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.config.NexusPaths;
import com.nexus.index.FileCategory;
import com.nexus.search.SearchDocument;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.search.semantic.SemanticVectorDocument;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qualification hermétique du watch item #50.
 *
 * <p>Le code de production reste volontairement operation-scoped. Les classes
 * persistantes de ce test ne sont que des prototypes de mesure : elles ne sont
 * jamais exposées au runtime NEXUS. Le benchmark compare les deux stratégies en
 * ordre ABBA afin de limiter le biais de chauffe, mesure lecture et micro-écriture
 * sur corpus lexical et sémantique, puis vérifie rollback + rebuild.</p>
 */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class LuceneLifecycleQualificationBenchmarkTest {

    private static final int DIMENSIONS = 32;
    private static final int SEARCH_LIMIT = 20;
    private static final int WARMUP_ROUNDS = 5;
    private static final int SAMPLE_ROUNDS = 20;
    private static final double MATERIAL_P95_IMPROVEMENT = 0.25d;
    private static final double MAX_WRITE_REGRESSION = 0.10d;

    private static final String[] LEXICAL_SEARCH_FIELDS = {
            "symbol_name", "qualified_name", "path_text", "code_terms", "content"
    };
    private static final Map<String, Float> LEXICAL_FIELD_BOOSTS = Map.of(
            "symbol_name", 5.0f,
            "qualified_name", 4.0f,
            "path_text", 3.0f,
            "code_terms", 2.0f,
            "content", 1.0f);

    @TempDir
    Path temporaryDirectory;

    @Test
    void qualifiesOperationScopedAgainstPersistentPrototype() throws Exception {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci")
                .trim()
                .toLowerCase(Locale.ROOT);
        boolean full = profile.equals("full");
        if (!full && !profile.equals("ci")) {
            throw new IllegalArgumentException("nexus.scale.benchmark.profile must be ci or full");
        }

        int lexicalDocuments = full ? 10_000 : 3_000;
        int semanticDocuments = full ? 8_000 : 2_000;

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("lifecycle-home"));
        LuceneSearchIndex lexical = new LuceneSearchIndex(paths);
        LuceneSemanticSearchIndex semantic = new LuceneSemanticSearchIndex(paths, DIMENSIONS);

        List<SearchDocument> lexicalCorpus = lexicalCorpus(lexicalDocuments);
        List<SemanticVectorDocument> semanticCorpus = semanticCorpus(semanticDocuments);
        float[] queryVector = vectorFor(0);

        UUID lexicalScopedSearchProject = UUID.randomUUID();
        UUID lexicalPersistentSearchProject = UUID.randomUUID();
        UUID lexicalScopedWriteProject = UUID.randomUUID();
        UUID lexicalPersistentWriteProject = UUID.randomUUID();
        UUID semanticScopedSearchProject = UUID.randomUUID();
        UUID semanticPersistentSearchProject = UUID.randomUUID();
        UUID semanticScopedWriteProject = UUID.randomUUID();
        UUID semanticPersistentWriteProject = UUID.randomUUID();

        lexical.rebuild(lexicalScopedSearchProject, lexicalCorpus);
        lexical.rebuild(lexicalPersistentSearchProject, lexicalCorpus);
        lexical.rebuild(lexicalScopedWriteProject, lexicalCorpus);
        lexical.rebuild(lexicalPersistentWriteProject, lexicalCorpus);
        semantic.rebuild(semanticScopedSearchProject, semanticCorpus);
        semantic.rebuild(semanticPersistentSearchProject, semanticCorpus);
        semantic.rebuild(semanticScopedWriteProject, semanticCorpus);
        semantic.rebuild(semanticPersistentWriteProject, semanticCorpus);

        long heapBeforePersistent = stableUsedHeapBytes();
        LifecycleMeasurements lexicalSearch;
        LifecycleMeasurements lexicalWrite;
        LifecycleMeasurements semanticSearch;
        LifecycleMeasurements semanticWrite;
        long heapWithPersistent;

        try (PersistentLexicalSearcher persistentLexicalSearcher =
                     new PersistentLexicalSearcher(paths.projectLuceneIndex(lexicalPersistentSearchProject));
             PersistentLexicalWriter persistentLexicalWriter =
                     new PersistentLexicalWriter(paths.projectLuceneIndex(lexicalPersistentWriteProject), lexicalPersistentWriteProject);
             PersistentSemanticSearcher persistentSemanticSearcher =
                     new PersistentSemanticSearcher(paths.projectSemanticLuceneIndex(semanticPersistentSearchProject));
             PersistentSemanticWriter persistentSemanticWriter =
                     new PersistentSemanticWriter(paths.projectSemanticLuceneIndex(semanticPersistentWriteProject))) {

            heapWithPersistent = stableUsedHeapBytes();

            lexicalSearch = measureAbba(
                    () -> lexical.search(lexicalScopedSearchProject, "LifecycleNeedle", SEARCH_LIMIT).size(),
                    () -> persistentLexicalSearcher.search("LifecycleNeedle", SEARCH_LIMIT));

            AtomicInteger lexicalScopedSequence = new AtomicInteger();
            AtomicInteger lexicalPersistentSequence = new AtomicInteger();
            lexicalWrite = measureAbba(
                    () -> {
                        lexical.applyChanges(
                                lexicalScopedWriteProject,
                                List.of(lexicalUpdate(lexicalScopedSequence.getAndIncrement())),
                                Set.of());
                        return 1;
                    },
                    () -> {
                        persistentLexicalWriter.update(lexicalUpdate(lexicalPersistentSequence.getAndIncrement()));
                        return 1;
                    });

            semanticSearch = measureAbba(
                    () -> semantic.search(semanticScopedSearchProject, queryVector, SEARCH_LIMIT).size(),
                    () -> persistentSemanticSearcher.search(queryVector, SEARCH_LIMIT));

            AtomicInteger semanticScopedSequence = new AtomicInteger();
            AtomicInteger semanticPersistentSequence = new AtomicInteger();
            semanticWrite = measureAbba(
                    () -> {
                        semantic.applyChanges(
                                semanticScopedWriteProject,
                                List.of(semanticUpdate(semanticScopedSequence.getAndIncrement())),
                                Set.of());
                        return 1;
                    },
                    () -> {
                        persistentSemanticWriter.update(semanticUpdate(semanticPersistentSequence.getAndIncrement()));
                        return 1;
                    });
        }

        Map<String, Object> recovery = qualifyRollbackAndRebuild(paths, lexical, semantic, lexicalCorpus, semanticCorpus);

        double lexicalSearchImprovement = lexicalSearch.p95ImprovementRatio();
        double semanticSearchImprovement = semanticSearch.p95ImprovementRatio();
        boolean materialSearchBenefit = lexicalSearchImprovement >= MATERIAL_P95_IMPROVEMENT
                && semanticSearchImprovement >= MATERIAL_P95_IMPROVEMENT;
        boolean writesWithinBudget = lexicalWrite.p95RegressionRatio() <= MAX_WRITE_REGRESSION
                && semanticWrite.p95RegressionRatio() <= MAX_WRITE_REGRESSION;
        boolean recoveryQualified = Boolean.TRUE.equals(recovery.get("lexical"))
                && Boolean.TRUE.equals(recovery.get("semantic"));
        boolean candidate = materialSearchBenefit && writesWithinBudget && recoveryQualified;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("profile", profile);
        report.put("protocol", Map.of(
                "warmupRounds", WARMUP_ROUNDS,
                "sampleRounds", SAMPLE_ROUNDS,
                "ordering", "ABBA",
                "searchLimit", SEARCH_LIMIT,
                "dimensions", DIMENSIONS));
        report.put("lexical", Map.of(
                "documents", lexicalDocuments,
                "search", lexicalSearch.asMap(),
                "microWrite", lexicalWrite.asMap(),
                "indexBytes", directorySize(paths.projectLuceneIndex(lexicalScopedSearchProject))));
        report.put("semantic", Map.of(
                "documents", semanticDocuments,
                "search", semanticSearch.asMap(),
                "microWrite", semanticWrite.asMap(),
                "indexBytes", directorySize(paths.projectSemanticLuceneIndex(semanticScopedSearchProject))));
        report.put("resources", Map.of(
                "operationScopedSteadyOpenDirectories", 0,
                "operationScopedSteadyOpenReaders", 0,
                "operationScopedSteadyOpenWriters", 0,
                "persistentPrototypeSteadyOpenDirectories", 4,
                "persistentPrototypeSteadyOpenReaders", 2,
                "persistentPrototypeSteadyOpenWriters", 2,
                "persistentPrototypeSteadyOpenAnalyzers", 1,
                "usedHeapBeforePersistentBytes", heapBeforePersistent,
                "usedHeapWithPersistentBytes", heapWithPersistent,
                "approximateRetainedHeapDeltaBytes", Math.max(0L, heapWithPersistent - heapBeforePersistent)));
        report.put("recovery", recovery);
        report.put("decision", Map.of(
                "materialP95ImprovementThreshold", MATERIAL_P95_IMPROVEMENT,
                "maximumWriteRegressionRatio", MAX_WRITE_REGRESSION,
                "materialSearchBenefit", materialSearchBenefit,
                "writesWithinBudget", writesWithinBudget,
                "recoveryQualified", recoveryQualified,
                "persistentLifecycleCandidate", candidate,
                "recommendation", candidate
                        ? "candidate_requires_repeated_linux_windows_qualification_and_adr_before_production_change"
                        : "retain_operation_scoped_lifecycle"));

        Path output = Path.of(System.getProperty(
                        "nexus.lucene.lifecycle.benchmark.output",
                        "target/lucene-lifecycle-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS Lucene lifecycle: profile=%s lexicalSearchP95Gain=%.3f semanticSearchP95Gain=%.3f "
                        + "lexicalWriteRegression=%.3f semanticWriteRegression=%.3f candidate=%s output=%s%n",
                profile,
                lexicalSearchImprovement,
                semanticSearchImprovement,
                lexicalWrite.p95RegressionRatio(),
                semanticWrite.p95RegressionRatio(),
                candidate,
                output);
    }

    private Map<String, Object> qualifyRollbackAndRebuild(
            NexusPaths paths,
            LuceneSearchIndex lexical,
            LuceneSemanticSearchIndex semantic,
            List<SearchDocument> lexicalCorpus,
            List<SemanticVectorDocument> semanticCorpus) throws Exception {
        UUID lexicalProject = UUID.randomUUID();
        lexical.rebuild(lexicalProject, lexicalCorpus);
        try (PersistentLexicalWriter writer =
                     new PersistentLexicalWriter(paths.projectLuceneIndex(lexicalProject), lexicalProject)) {
            writer.stage(lexicalUpdate(999_999));
            writer.rollback();
        }
        lexical.rebuild(lexicalProject, lexicalCorpus);
        boolean lexicalRecovered = !lexical.search(lexicalProject, "LifecycleNeedle", SEARCH_LIMIT).isEmpty();

        UUID semanticProject = UUID.randomUUID();
        semantic.rebuild(semanticProject, semanticCorpus);
        try (PersistentSemanticWriter writer =
                     new PersistentSemanticWriter(paths.projectSemanticLuceneIndex(semanticProject))) {
            writer.stage(semanticUpdate(999_999));
            writer.rollback();
        }
        semantic.rebuild(semanticProject, semanticCorpus);
        boolean semanticRecovered = !semantic.search(semanticProject, vectorFor(0), SEARCH_LIMIT).isEmpty();

        assertTrue(lexicalRecovered, "Lexical index must rebuild after a rolled-back persistent writer");
        assertTrue(semanticRecovered, "Semantic index must rebuild after a rolled-back persistent writer");
        return Map.of(
                "lexical", lexicalRecovered,
                "semantic", semanticRecovered,
                "scenario", "persistent_writer_rollback_then_production_rebuild");
    }

    private static LifecycleMeasurements measureAbba(ThrowingIntSupplier scoped, ThrowingIntSupplier persistent)
            throws Exception {
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            assertPositive(scoped.get());
            assertPositive(persistent.get());
            assertPositive(persistent.get());
            assertPositive(scoped.get());
        }

        List<Long> scopedMicros = new ArrayList<>(SAMPLE_ROUNDS * 2);
        List<Long> persistentMicros = new ArrayList<>(SAMPLE_ROUNDS * 2);
        for (int round = 0; round < SAMPLE_ROUNDS; round++) {
            recordMeasurement(scoped, scopedMicros);
            recordMeasurement(persistent, persistentMicros);
            recordMeasurement(persistent, persistentMicros);
            recordMeasurement(scoped, scopedMicros);
        }
        return new LifecycleMeasurements(
                Measurement.fromMicros(scopedMicros),
                Measurement.fromMicros(persistentMicros));
    }

    private static void recordMeasurement(ThrowingIntSupplier operation, List<Long> samples) throws Exception {
        long started = System.nanoTime();
        int result = operation.get();
        samples.add(elapsedMicros(started));
        assertPositive(result);
    }

    private static void assertPositive(int result) {
        assertTrue(result > 0, "Benchmark operation must produce a positive result");
    }

    private static List<SearchDocument> lexicalCorpus(int count) {
        List<SearchDocument> documents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String marker = index % 17 == 0 ? " LifecycleNeedle " : " ";
            documents.add(new SearchDocument(
                    "src/generated/LifecycleDoc" + index + ".java",
                    "java",
                    FileCategory.SOURCE,
                    "package bench.lifecycle; class LifecycleDoc" + index
                            + " { String value = \"" + marker + "shared context " + index + "\"; }",
                    List.of()));
        }
        return List.copyOf(documents);
    }

    private static SearchDocument lexicalUpdate(int sequence) {
        return new SearchDocument(
                "src/generated/LifecycleDoc0.java",
                "java",
                FileCategory.SOURCE,
                "package bench.lifecycle; class LifecycleDoc0 { String value = \"LifecycleNeedle update "
                        + sequence + "\"; }",
                List.of());
    }

    private static List<SemanticVectorDocument> semanticCorpus(int count) {
        List<SemanticVectorDocument> documents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            documents.add(new SemanticVectorDocument(
                    "src/generated/SemanticLifecycleDoc" + index + ".java",
                    FileCategory.SOURCE,
                    "semantic lifecycle document " + index,
                    vectorFor(index)));
        }
        return List.copyOf(documents);
    }

    private static SemanticVectorDocument semanticUpdate(int sequence) {
        return new SemanticVectorDocument(
                "src/generated/SemanticLifecycleDoc0.java",
                FileCategory.SOURCE,
                "semantic lifecycle update " + sequence,
                vectorFor(sequence + 10_000));
    }

    private static float[] vectorFor(int seed) {
        float[] vector = new float[DIMENSIONS];
        double squared = 0.0d;
        for (int dimension = 0; dimension < vector.length; dimension++) {
            float value = ((seed * 31 + dimension * 17) % 97) + 1.0f;
            vector[dimension] = value;
            squared += value * value;
        }
        float norm = (float) Math.sqrt(squared);
        for (int dimension = 0; dimension < vector.length; dimension++) {
            vector[dimension] /= norm;
        }
        return vector;
    }

    private static long stableUsedHeapBytes() throws InterruptedException {
        System.gc();
        Thread.sleep(25L);
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long directorySize(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException exception) {
                    throw new DirectorySizeFailure(exception);
                }
            }).sum();
        } catch (DirectorySizeFailure failure) {
            throw failure.ioException();
        }
    }

    private static long elapsedMicros(long started) {
        return Math.max(1L, (System.nanoTime() - started) / 1_000L);
    }

    private static Document lexicalLuceneDocument(UUID projectId, SearchDocument source) {
        Document document = new Document();
        document.add(new StringField("document_key", documentKey(projectId, source.relativePath()), Field.Store.NO));
        document.add(new StringField("project_id", projectId.toString(), Field.Store.NO));
        document.add(new StringField("path", source.relativePath(), Field.Store.YES));
        document.add(new TextField("path_text", source.relativePath(), Field.Store.NO));
        document.add(new StringField("language", source.language(), Field.Store.YES));
        document.add(new StringField("category", source.category().name(), Field.Store.YES));
        document.add(new TextField("content", source.content(), Field.Store.NO));
        document.add(new TextField("code_terms", source.relativePath() + " " + source.content(), Field.Store.NO));
        return document;
    }

    private static Document semanticLuceneDocument(SemanticVectorDocument source) {
        Document document = new Document();
        document.add(new StringField("path", source.relativePath(), Field.Store.YES));
        document.add(new StringField("category", source.category().name(), Field.Store.YES));
        document.add(new StoredField("excerpt", source.excerpt()));
        document.add(new KnnFloatVectorField(
                "embedding",
                source.vector(),
                VectorSimilarityFunction.COSINE));
        return document;
    }

    private static String documentKey(UUID projectId, String relativePath) {
        return projectId + ":" + relativePath;
    }

    private static final class PersistentLexicalSearcher implements AutoCloseable {
        private final Directory directory;
        private final DirectoryReader reader;
        private final Analyzer analyzer;
        private final IndexSearcher searcher;

        private PersistentLexicalSearcher(Path indexPath) throws IOException {
            directory = FSDirectory.open(indexPath);
            reader = DirectoryReader.open(directory);
            analyzer = new StandardAnalyzer();
            searcher = new IndexSearcher(reader);
        }

        private int search(String queryText, int limit) throws Exception {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    LEXICAL_SEARCH_FIELDS,
                    analyzer,
                    LEXICAL_FIELD_BOOSTS);
            parser.setDefaultOperator(QueryParser.Operator.OR);
            Query query = parser.parse(QueryParser.escape(queryText));
            return searcher.search(query, limit).scoreDocs.length;
        }

        @Override
        public void close() throws IOException {
            reader.close();
            analyzer.close();
            directory.close();
        }
    }

    private static final class PersistentLexicalWriter implements AutoCloseable {
        private final Directory directory;
        private final Analyzer analyzer;
        private final IndexWriter writer;
        private final UUID projectId;
        private boolean terminal;

        private PersistentLexicalWriter(Path indexPath, UUID projectId) throws IOException {
            this.projectId = projectId;
            directory = FSDirectory.open(indexPath);
            analyzer = new StandardAnalyzer();
            writer = new IndexWriter(
                    directory,
                    new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
        }

        private void update(SearchDocument document) throws IOException {
            stage(document);
            writer.commit();
        }

        private void stage(SearchDocument document) throws IOException {
            writer.updateDocument(
                    new Term("document_key", documentKey(projectId, document.relativePath())),
                    lexicalLuceneDocument(projectId, document));
        }

        private void rollback() throws IOException {
            writer.rollback();
            terminal = true;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            if (!terminal) {
                try {
                    writer.close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            analyzer.close();
            try {
                directory.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class PersistentSemanticSearcher implements AutoCloseable {
        private final Directory directory;
        private final DirectoryReader reader;
        private final IndexSearcher searcher;

        private PersistentSemanticSearcher(Path indexPath) throws IOException {
            directory = FSDirectory.open(indexPath);
            reader = DirectoryReader.open(directory);
            searcher = new IndexSearcher(reader);
        }

        private int search(float[] queryVector, int limit) throws IOException {
            int k = Math.min(limit, reader.numDocs());
            Query query = KnnFloatVectorField.newVectorQuery("embedding", queryVector, k);
            return searcher.search(query, k).scoreDocs.length;
        }

        @Override
        public void close() throws IOException {
            reader.close();
            directory.close();
        }
    }

    private static final class PersistentSemanticWriter implements AutoCloseable {
        private final Directory directory;
        private final IndexWriter writer;
        private boolean terminal;

        private PersistentSemanticWriter(Path indexPath) throws IOException {
            directory = FSDirectory.open(indexPath);
            writer = new IndexWriter(
                    directory,
                    new IndexWriterConfig().setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND));
        }

        private void update(SemanticVectorDocument document) throws IOException {
            stage(document);
            writer.commit();
        }

        private void stage(SemanticVectorDocument document) throws IOException {
            writer.deleteDocuments(new Term("path", document.relativePath()));
            writer.addDocument(semanticLuceneDocument(document));
        }

        private void rollback() throws IOException {
            writer.rollback();
            terminal = true;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            if (!terminal) {
                try {
                    writer.close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            try {
                directory.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record LifecycleMeasurements(Measurement operationScoped, Measurement persistentPrototype) {
        private double p95ImprovementRatio() {
            return operationScoped.p95Micros() <= 0L
                    ? 0.0d
                    : (operationScoped.p95Micros() - persistentPrototype.p95Micros())
                    / (double) operationScoped.p95Micros();
        }

        private double p95RegressionRatio() {
            return operationScoped.p95Micros() <= 0L
                    ? 0.0d
                    : (persistentPrototype.p95Micros() - operationScoped.p95Micros())
                    / (double) operationScoped.p95Micros();
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("operationScoped", operationScoped.asMap());
            result.put("persistentPrototype", persistentPrototype.asMap());
            result.put("p95ImprovementRatio", p95ImprovementRatio());
            result.put("p95RegressionRatio", p95RegressionRatio());
            return result;
        }
    }

    private record Measurement(long p50Micros, long p95Micros, long meanMicros, int samples) {
        private static Measurement fromMicros(List<Long> source) {
            assertFalse(source.isEmpty());
            List<Long> sorted = source.stream().sorted(Comparator.naturalOrder()).toList();
            long sum = source.stream().mapToLong(Long::longValue).sum();
            return new Measurement(
                    percentile(sorted, 0.50d),
                    percentile(sorted, 0.95d),
                    Math.max(1L, Math.round(sum / (double) source.size())),
                    source.size());
        }

        private static long percentile(List<Long> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(Math.min(index, sorted.size() - 1));
        }

        private Map<String, Object> asMap() {
            return Map.of(
                    "p50Micros", p50Micros,
                    "p95Micros", p95Micros,
                    "meanMicros", meanMicros,
                    "samples", samples);
        }
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int get() throws Exception;
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
