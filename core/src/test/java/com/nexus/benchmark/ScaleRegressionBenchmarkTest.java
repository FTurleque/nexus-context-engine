package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexWriteSession;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.persistence.sqlite.SqliteBatchInsert;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticIndexingService;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final int QUERY_SAMPLES = 20;
    private static final int POPULATION_WARMUPS = 2;
    private static final int SMALL_POPULATION_SAMPLES = 3;
    private static final int DEDICATED_POPULATION_SAMPLES = 3;
    private static final int SEARCH_LIMIT = 20;
    private static final int PORTFOLIO_FILES_PER_PROJECT = 2;
    private static final int PORTFOLIO_CONTEXT_BUDGET = 2_400;
    private static final int SEMANTIC_DIMENSIONS = 32;
    private static final int REINDEX_SYMBOLS = 100_000;
    private static final int REINDEX_INCREMENTAL_FILES = 50;
    private static final int REINDEX_SAMPLES = 3;
    private static final List<String> SCENARIOS = List.of("sqliteTiers", "portfolio", "concurrentReadWrite",
            "semanticRecovery", "reindexExistingFiles", "stagedPublication", "singleProjectFuzzy");
    /**
     * Fichiers par lot de mise en attente, comme l'indexation (128, ADR-0051). Le harnais est aussi compilé sur
     * le code de base d'une PR : il ne dépend que des types publics déjà présents sur cette base.
     */
    private static final int STAGE_BATCH_FILES = 128;
    private static final int FUZZY_SYMBOLS_PER_FILE = 5;
    /** Requête entière puis 8 termes, comme une recherche de symboles de SymbolSearchStrategy. */
    private static final List<String> FUZZY_TERMS = List.of("parseconfigfile", "parse", "config", "file",
            "renderer", "loadindex", "graphnode", "tokenize", "mergerank");
    /** Plafond du comptage de {@code SqliteSymbolSearch} : seuil du parcours ordonné plus un. */
    private static final long FUZZY_CANDIDATE_CAP = 10_001L;
    private static final String FILE_SCOPED_FUZZY_INDEX = "idx_symbols_file_fuzzy_prefilter";
    private static final String GLOBAL_FUZZY_INDEX = "idx_symbols_fuzzy_prefilter";
    /** Comptage plafonné du préfiltre ; mêmes clauses que {@code SqliteSymbolSearch.FUZZY_COUNT_ARM_SQL}. */
    private static final String FUZZY_COUNT_ARM_SQL = """
            SELECT ? AS term_index, COUNT(*) AS candidate_count FROM (
                SELECT 1
                FROM symbols s INDEXED BY %s
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                LIMIT ?)
            """;
    /** Récupération du préfiltre ; mêmes clauses que {@code SqliteSymbolSearch.FUZZY_BATCH_SQL}. */
    private static final String FUZZY_BATCH_ARM_SQL = """
            SELECT * FROM (
                SELECT ? AS term_index, s.qualified_name AS qualified_name
                FROM symbols s INDEXED BY %s
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?)
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresHermeticScaleProfile() throws Exception {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci").trim().toLowerCase(Locale.ROOT);
        boolean full = profile.equals("full");
        boolean sqliteOnly = Boolean.getBoolean("nexus.scale.benchmark.sqliteOnly");
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

        int stagedPublicationFiles = full ? 100_000 : 50_000;
        int singleProjectFuzzyFiles = full ? 100_000 : 20_000;
        Set<String> scenarios = selectedScenarios(sqliteOnly);

        long benchmarkStarted = System.nanoTime();
        long usedHeapBefore = usedHeapBytes();

        // Les deux JVM (base et candidat) chauffent le même chemin sur des bases
        // distinctes. Le démarrage à froid reste visible dans le rapport.
        List<Long> populationWarmups = new ArrayList<>();
        List<Map<String, Object>> sqlite = new ArrayList<>();
        if (scenarios.contains("sqliteTiers")) {
            for (int iteration = 0; iteration < POPULATION_WARMUPS; iteration++) {
                SqliteDatabase warmup = new SqliteDatabase(new NexusPaths(
                        temporaryDirectory.resolve("population-warmup-" + iteration)));
                setJournalMode(warmup, "DELETE");
                long started = System.nanoTime();
                populateProject(warmup, UUID.randomUUID(), "warmup", 10_000, 10_000);
                populationWarmups.add(elapsedMillis(started));
            }
            for (int symbolCount : sqliteTiers) {
                sqlite.add(benchmarkSqliteTier(symbolCount, sqliteOnly));
            }
        }

        Map<String, Object> portfolio = scenarios.contains("portfolio") ? benchmarkPortfolio(portfolioTiers) : Map.of();
        Map<String, Object> concurrency = scenarios.contains("concurrentReadWrite")
                ? benchmarkJournalModes(concurrencySymbolsPerProject) : Map.of();
        Map<String, Object> semantic = scenarios.contains("semanticRecovery")
                ? benchmarkSemanticRecovery(semanticDocuments) : Map.of();
        Map<String, Object> reindex = scenarios.contains("reindexExistingFiles")
                ? benchmarkReindexExistingFiles() : Map.of();
        Map<String, Object> stagedPublication = scenarios.contains("stagedPublication")
                ? benchmarkStagedPublication(stagedPublicationFiles) : Map.of();
        Map<String, Object> singleProjectFuzzy = scenarios.contains("singleProjectFuzzy")
                ? benchmarkSingleProjectFuzzy(singleProjectFuzzyFiles) : Map.of();

        long usedHeapAfter = usedHeapBytes();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("profile", profile);
        report.put("sqliteOnly", sqliteOnly);
        report.put("scenarios", List.copyOf(scenarios));
        report.put("populationWarmupSamplesMs", populationWarmups);
        report.put("environment", environment());
        report.put("protocol", Map.of(
                "version", 6,
                "population", Map.of(
                        "warmups", POPULATION_WARMUPS,
                        "smallTierSamples", SMALL_POPULATION_SAMPLES,
                        "largeTierSamples", sqliteOnly ? DEDICATED_POPULATION_SAMPLES : 1),
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
        report.put("reindexExistingFiles", reindex);
        report.put("stagedPublication", stagedPublication);
        report.put("singleProjectFuzzy", singleProjectFuzzy);
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

    private Map<String, Object> benchmarkSqliteTier(int symbolCount, boolean sqliteOnly) throws Exception {
        List<Long> populationSamples = new ArrayList<>();
        int largeSampleCount = sqliteOnly ? DEDICATED_POPULATION_SAMPLES : 1;
        int sampleCount = symbolCount == 10_000 ? SMALL_POPULATION_SAMPLES : largeSampleCount;
        SqliteDatabase database = null;
        UUID projectId = null;
        String journalMode = null;
        for (int sample = 0; sample < sampleCount; sample++) {
            Path home = temporaryDirectory.resolve("sqlite-" + symbolCount + "-sample-" + sample);
            database = new SqliteDatabase(new NexusPaths(home));
            journalMode = setJournalMode(database, "DELETE");
            projectId = UUID.randomUUID();
            long populateStarted = System.nanoTime();
            populateProject(database, projectId, "sqlite-" + symbolCount, symbolCount, symbolCount);
            populationSamples.add(elapsedMillis(populateStarted));
        }
        long populationMs = percentile(populationSamples, 0.5d);
        UUID measuredProjectId = projectId;

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        Measurement exact = measure(() -> repository.searchSymbols(measuredProjectId, "BenchSymbol00000010", SEARCH_LIMIT));
        Measurement contains = measure(() -> repository.searchSymbols(measuredProjectId, "ScaleNeedle", SEARCH_LIMIT));
        Measurement missing = measure(() -> repository.searchSymbols(measuredProjectId, "DefinitelyAbsentScaleToken", SEARCH_LIMIT));
        Measurement relations = measure(() -> repository.searchRelations(measuredProjectId, "TargetNeedle", SEARCH_LIMIT));

        int fileCount = Math.max(1, (symbolCount + SYMBOLS_PER_FILE - 1) / SYMBOLS_PER_FILE);
        Set<String> targetedPaths = java.util.stream.IntStream.range(0, Math.min(100, fileCount))
                .mapToObj(index -> filePath(index))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Measurement targetedFiles = measure(() -> repository.findFiles(measuredProjectId, targetedPaths));

        assertFalse(repository.searchSymbols(projectId, "ScaleNeedle", SEARCH_LIMIT).isEmpty());
        assertFalse(repository.searchRelations(projectId, "TargetNeedle", SEARCH_LIMIT).isEmpty());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbols", symbolCount);
        result.put("relations", symbolCount);
        result.put("files", fileCount);
        result.put("journalMode", journalMode);
        result.put("populationMs", populationMs);
        result.put("populationSamplesMs", populationSamples);
        result.put("databaseBytes", sqliteFilesSize(database.databaseFile()));
        result.put("symbolExact", exact.asMap());
        result.put("symbolContains", contains.asMap());
        result.put("symbolMissingWorstCase", missing.asMap());
        result.put("relationContains", relations.asMap());
        result.put("targeted100Files", targetedFiles.asMap());
        return result;
    }

    /**
     * Réindexe des fichiers déjà présents par le chemin d'écriture de l'indexation (session ouverte,
     * analyses mises en attente par lots, publication) sur une base peuplée : une réindexation
     * incrémentale (quelques fichiers) puis un rebuild (tous les fichiers). Chaque fichier supprime puis
     * réécrit ses symboles et relations ; le coût doit rester proportionnel aux fichiers modifiés, non à
     * la taille de la base. {@code medianMs} couvre toute la session, {@code publishMedianMs} la seule
     * transaction de publication.
     */
    private Map<String, Object> benchmarkReindexExistingFiles() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("reindex-home")));
        setJournalMode(database, "DELETE");
        UUID projectId = UUID.randomUUID();
        populateProject(database, projectId, "reindex", REINDEX_SYMBOLS, REINDEX_SYMBOLS);
        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        int fileCount = Math.max(1, (REINDEX_SYMBOLS + SYMBOLS_PER_FILE - 1) / SYMBOLS_PER_FILE);
        int incrementalFiles = Math.min(REINDEX_INCREMENTAL_FILES, fileCount);

        List<Long> incrementalSamples = new ArrayList<>();
        List<Long> incrementalPublishSamples = new ArrayList<>();
        List<Long> rebuildSamples = new ArrayList<>();
        List<Long> rebuildPublishSamples = new ArrayList<>();
        for (int sample = 0; sample < REINDEX_SAMPLES; sample++) {
            List<IndexedFileUpdate> incremental = reindexUpdates(fileCount, incrementalFiles, sample);
            long started = System.nanoTime();
            incrementalPublishSamples.add(writeBySession(repository, projectId, incremental) / 1_000L);
            incrementalSamples.add(elapsedMillis(started));

            List<IndexedFileUpdate> rebuild = reindexUpdates(fileCount, fileCount, sample);
            started = System.nanoTime();
            rebuildPublishSamples.add(writeBySession(repository, projectId, rebuild) / 1_000L);
            rebuildSamples.add(elapsedMillis(started));
        }

        var statistics = repository.statistics(projectId);
        assertTrue(statistics.files() == fileCount, "La réindexation ne doit ni ajouter ni perdre de fichier");
        assertTrue(statistics.symbols() == REINDEX_SYMBOLS, "La réindexation remplace les symboles sans les dupliquer");
        assertTrue(statistics.relations() == REINDEX_SYMBOLS, "La réindexation remplace les relations sans les dupliquer");

        Map<String, Object> incrementalResult = new LinkedHashMap<>();
        incrementalResult.put("files", incrementalFiles);
        incrementalResult.put("samplesMs", incrementalSamples);
        incrementalResult.put("medianMs", percentile(incrementalSamples, 0.5d));
        incrementalResult.put("publishSamplesMs", incrementalPublishSamples);
        incrementalResult.put("publishMedianMs", percentile(incrementalPublishSamples, 0.5d));
        Map<String, Object> rebuildResult = new LinkedHashMap<>();
        rebuildResult.put("files", fileCount);
        rebuildResult.put("samplesMs", rebuildSamples);
        rebuildResult.put("medianMs", percentile(rebuildSamples, 0.5d));
        rebuildResult.put("publishSamplesMs", rebuildPublishSamples);
        rebuildResult.put("publishMedianMs", percentile(rebuildPublishSamples, 0.5d));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writePath", "openWriteSession/stage/publish");
        result.put("populatedSymbols", REINDEX_SYMBOLS);
        result.put("populatedRelations", REINDEX_SYMBOLS);
        result.put("populatedFiles", fileCount);
        result.put("symbolsPerFile", SYMBOLS_PER_FILE);
        result.put("incremental", incrementalResult);
        result.put("rebuild", rebuildResult);
        result.put("databaseBytes", sqliteFilesSize(database.databaseFile()));
        return result;
    }

    /**
     * Écrit des analyses comme l'indexation : session ouverte, mise en attente par lots de
     * {@link #STAGE_BATCH_FILES} fichiers, publication. Rend la durée de la publication en
     * microsecondes.
     */
    private static long writeBySession(IndexRepository repository, UUID projectId, List<IndexedFileUpdate> updates) {
        try (IndexWriteSession session = repository.openWriteSession(projectId)) {
            for (int from = 0; from < updates.size(); from += STAGE_BATCH_FILES) {
                session.stage(updates.subList(from, Math.min(updates.size(), from + STAGE_BATCH_FILES)));
            }
            long started = System.nanoTime();
            session.publish(Set.of());
            return elapsedMicros(started);
        }
    }

    /**
     * Publication de l'indexation complète d'un projet neuf de {@code fileCount} fichiers (AUD-PRF-27) :
     * la transaction de publication relit la mise en attente par pages ; sa durée doit croître avec le
     * nombre de fichiers, non avec son carré.
     */
    private Map<String, Object> benchmarkStagedPublication(int fileCount) throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("staged-publication")));
        setJournalMode(database, "DELETE");
        UUID projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection();
             PreparedStatement project = connection.prepareStatement("""
                     INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                     VALUES (?, ?, ?, 'LOCAL', NULL, 'READY')
                     """)) {
            project.setString(1, projectId.toString());
            project.setString(2, "staged-publication-" + projectId);
            project.setString(3, temporaryDirectory.resolve("staged-project-" + projectId).toString());
            project.executeUpdate();
        }
        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        Path root = temporaryDirectory.resolve("staged-sources");
        long started = System.nanoTime();
        long stagingMicros = 0L;
        long publishMicros;
        try (IndexWriteSession session = repository.openWriteSession(projectId)) {
            for (int from = 0; from < fileCount; from += STAGE_BATCH_FILES) {
                List<IndexedFileUpdate> batch = new ArrayList<>(STAGE_BATCH_FILES);
                for (int file = from; file < Math.min(fileCount, from + STAGE_BATCH_FILES); file++) {
                    String relativePath = filePath(file);
                    String name = String.format(Locale.ROOT, "StagedSymbol%08d", file);
                    Path absolute = root.resolve(relativePath);
                    batch.add(new IndexedFileUpdate(
                            new ScannedFile(absolute, relativePath, "java", 128, "staged-hash-" + file,
                                    Instant.EPOCH.plusSeconds(file), 32, FileCategory.SOURCE),
                            new AnalysisResult(absolute, "java",
                                    List.of(new CodeSymbol(SymbolKind.CLASS, name, "bench.staged." + name,
                                            "class " + name, 1, 2)),
                                    List.of(new SymbolRelation(RelationKind.USES, "bench.staged." + name,
                                            "bench.target.Target" + file)))));
                }
                long stageStarted = System.nanoTime();
                session.stage(batch);
                stagingMicros += elapsedMicros(stageStarted);
            }
            long publishStarted = System.nanoTime();
            session.publish(Set.of());
            publishMicros = elapsedMicros(publishStarted);
        }
        long totalMs = elapsedMillis(started);
        var statistics = repository.statistics(projectId);
        assertEquals(fileCount, statistics.files(), "La publication doit écrire chaque fichier mis en attente");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("writePath", "openWriteSession/stage/publish");
        result.put("files", fileCount);
        result.put("symbolsPerFile", 1);
        result.put("relationsPerFile", 1);
        result.put("stageBatchFiles", STAGE_BATCH_FILES);
        result.put("stagingMs", stagingMicros / 1_000L);
        result.put("publishMs", publishMicros / 1_000L);
        result.put("totalMs", totalMs);
        result.put("databaseBytes", sqliteFilesSize(database.databaseFile()));
        return result;
    }

    /**
     * Recherche approchée d'un projet seul dans sa base (AUD-PRF-29). La recherche de production des 9 termes
     * d'une recherche de symboles est mesurée sur le schéma courant ; le comptage plafonné et la récupération
     * du préfiltre approché le sont avec l'index scopé par fichier ({@code idx_symbols_file_fuzzy_prefilter},
     * V015) forcé, puis sur le schéma qui précède V015 : index scopé supprimé, index global
     * {@code idx_symbols_fuzzy_prefilter} présent (recréé s'il manque, comme sur le code d'avant V017). Le
     * harnais porte les requêtes de {@code SqliteSymbolSearch} avec l'index forcé en paramètre, ce qui le
     * rend exécutable sur le code de base d'une PR.
     */
    private Map<String, Object> benchmarkSingleProjectFuzzy(int fileCount) throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("single-project-fuzzy")));
        setJournalMode(database, "DELETE");
        UUID projectId = UUID.randomUUID();
        long populateStarted = System.nanoTime();
        populateFuzzyProject(database, projectId, fileCount);
        long populationMs = elapsedMillis(populateStarted);

        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        Measurement production = measure(() -> repository.searchSymbols(projectId, FUZZY_TERMS, SEARCH_LIMIT));
        Map<String, Object> fileScoped = measureFuzzyArms(database, projectId, FILE_SCOPED_FUZZY_INDEX);
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS " + FILE_SCOPED_FUZZY_INDEX);
            statement.execute("CREATE INDEX IF NOT EXISTS " + GLOBAL_FUZZY_INDEX
                    + " ON symbols(SUBSTR(LOWER(name), 1, 1), LENGTH(name))");
        }
        Map<String, Object> global = measureFuzzyArms(database, projectId, GLOBAL_FUZZY_INDEX);
        assertTrue(fileScoped.get("candidateCounts").equals(global.get("candidateCounts"))
                        && fileScoped.get("retrievedRows").equals(global.get("retrievedRows")),
                "Les deux index doivent rendre les mêmes candidats");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectsInDatabase", 1);
        result.put("files", fileCount);
        result.put("symbols", fileCount * FUZZY_SYMBOLS_PER_FILE);
        result.put("symbolsPerFile", FUZZY_SYMBOLS_PER_FILE);
        result.put("terms", FUZZY_TERMS);
        result.put("populationMs", populationMs);
        result.put("productionSearch", production.asMap());
        result.put("fileScopedPrefilter", fileScoped);
        result.put("globalPrefilterSchemaBeforeV015", global);
        result.put("fileScopedCountSlowdownRatio", slowdown(global, fileScoped, "count"));
        result.put("fileScopedRetrievalSlowdownRatio", slowdown(global, fileScoped, "retrieval"));
        result.put("databaseBytes", sqliteFilesSize(database.databaseFile()));
        return result;
    }

    /** Rapport des médianes : index scopé sur index global ; au-delà de 1, l'index scopé est plus lent. */
    private static double slowdown(Map<String, Object> global, Map<String, Object> fileScoped, String measurement) {
        long reference = ((Number) ((Map<?, ?>) global.get(measurement)).get("p50Micros")).longValue();
        long measured = ((Number) ((Map<?, ?>) fileScoped.get(measurement)).get("p50Micros")).longValue();
        return reference <= 0L ? 0.0d : (double) measured / reference;
    }

    private static Map<String, Object> measureFuzzyArms(SqliteDatabase database, UUID projectId, String index)
            throws Exception {
        String countSql = String.join(" UNION ALL ",
                Collections.nCopies(FUZZY_TERMS.size(), FUZZY_COUNT_ARM_SQL.formatted(index)));
        String retrievalSql = String.join(" UNION ALL ",
                Collections.nCopies(FUZZY_TERMS.size(), FUZZY_BATCH_ARM_SQL.formatted(index)));
        try (Connection connection = database.openConnection();
             PreparedStatement count = connection.prepareStatement(countSql);
             PreparedStatement retrieval = connection.prepareStatement(retrievalSql)) {
            bindFuzzyArms(count, projectId, FUZZY_CANDIDATE_CAP);
            bindFuzzyArms(retrieval, projectId, SEARCH_LIMIT);
            Measurement countMeasurement = measure(() -> readCounts(count));
            Measurement retrievalMeasurement = measure(() -> readRows(retrieval));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", index);
            result.put("count", countMeasurement.asMap());
            result.put("retrieval", retrievalMeasurement.asMap());
            result.put("candidateCounts", readCounts(count));
            result.put("retrievedRows", readRows(retrieval));
            result.put("countPlan", explain(connection, countSql, projectId, FUZZY_CANDIDATE_CAP));
            return result;
        }
    }

    private static void bindFuzzyArms(PreparedStatement statement, UUID projectId, long limit) throws SQLException {
        int position = 1;
        for (int term = 0; term < FUZZY_TERMS.size(); term++) {
            String normalized = FUZZY_TERMS.get(term);
            int length = normalized.codePointCount(0, normalized.length());
            statement.setInt(position++, term);
            statement.setString(position++, projectId.toString());
            statement.setString(position++, normalized.substring(0, 1));
            statement.setInt(position++, Math.max(0, length - 3));
            statement.setInt(position++, length + 3);
            statement.setLong(position++, limit);
        }
    }

    private static List<Long> readCounts(PreparedStatement count) throws SQLException {
        List<Long> counts = new ArrayList<>();
        try (ResultSet resultSet = count.executeQuery()) {
            while (resultSet.next()) {
                counts.add(resultSet.getLong("candidate_count"));
            }
        }
        return counts;
    }

    private static List<String> readRows(PreparedStatement retrieval) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet resultSet = retrieval.executeQuery()) {
            while (resultSet.next()) {
                rows.add(resultSet.getInt("term_index") + ":" + resultSet.getString("qualified_name"));
            }
        }
        return rows;
    }

    private static List<String> explain(Connection connection, String sql, UUID projectId, long limit)
            throws SQLException {
        List<String> plan = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            bindFuzzyArms(statement, projectId, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    plan.add(resultSet.getString("detail"));
                }
            }
        }
        return plan;
    }

    /**
     * Un projet seul, {@link #FUZZY_SYMBOLS_PER_FILE} symboles par fichier. Les noms varient par premier
     * caractère (26) et par longueur (6 à 25) : chaque terme de {@link #FUZZY_TERMS} a pour candidats
     * environ 1,3 % des symboles, sous le seuil du parcours ordonné de {@code SqliteSymbolSearch}.
     */
    private void populateFuzzyProject(SqliteDatabase database, UUID projectId, int fileCount) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement project = connection.prepareStatement("""
                        INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                        VALUES (?, ?, ?, 'LOCAL', ?, 'READY')
                        """)) {
                    project.setString(1, projectId.toString());
                    project.setString(2, "single-project-fuzzy-" + projectId);
                    project.setString(3, temporaryDirectory.resolve("fuzzy-project-" + projectId).toString());
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
                        files.setString(3, "fuzzy-hash-" + file);
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
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id FROM indexed_files WHERE project_id = ? ORDER BY id")) {
                    select.setString(1, projectId.toString());
                    try (ResultSet resultSet = select.executeQuery()) {
                        while (resultSet.next()) {
                            fileIds.add(resultSet.getLong(1));
                        }
                    }
                }
                int symbolCount = fileCount * FUZZY_SYMBOLS_PER_FILE;
                try (var symbols = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS)) {
                    for (int symbol = 0; symbol < symbolCount; symbol++) {
                        String name = fuzzySymbolName(symbol);
                        String qualified = "bench.fuzzy.p" + (symbol % 97) + "." + name;
                        symbols.add(fileIds.get(symbol / FUZZY_SYMBOLS_PER_FILE), "METHOD", name, qualified,
                                "void " + name + "()", 1, 2, "javaparser");
                        if ((symbol + 1) % 50_000 == 0) {
                            symbols.flush();
                            connection.commit();
                        }
                    }
                    symbols.flush();
                    connection.commit();
                }
                try (PreparedStatement generation = connection.prepareStatement(
                        "UPDATE project_index_generations SET generation = generation + 1 WHERE project_id = ?")) {
                    generation.setString(1, projectId.toString());
                    generation.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /** Nom déterministe : premier caractère parmi 26, longueur de 6 à 25, suffixe distinct par symbole. */
    private static String fuzzySymbolName(int symbol) {
        char first = (char) ('a' + symbol % 26);
        int length = 6 + (symbol / 26) % 20;
        StringBuilder name = new StringBuilder(length).append(first);
        String suffix = Integer.toString(symbol, 36);
        while (name.length() + suffix.length() < length) {
            name.append('x');
        }
        name.append(suffix);
        return name.toString();
    }

    /**
     * Scénarios exécutés : {@code nexus.scale.benchmark.scenarios} (liste séparée par des virgules) quand il
     * est fourni ; sinon les paliers SQLite seuls en {@code sqliteOnly}, tous les scénarios autrement.
     */
    private static Set<String> selectedScenarios(boolean sqliteOnly) {
        String requested = System.getProperty("nexus.scale.benchmark.scenarios", "").trim();
        if (!requested.isEmpty()) {
            Set<String> selected = new LinkedHashSet<>();
            for (String scenario : requested.split(",")) {
                String name = scenario.trim();
                if (!SCENARIOS.contains(name)) {
                    throw new IllegalArgumentException("Unknown nexus.scale.benchmark.scenarios entry: " + name);
                }
                selected.add(name);
            }
            return selected;
        }
        return sqliteOnly ? Set.of("sqliteTiers") : new LinkedHashSet<>(SCENARIOS);
    }

    /** Mises à jour de {@code count} fichiers existants, chacun avec ses symboles et relations. */
    private List<IndexedFileUpdate> reindexUpdates(int fileCount, int count, int generation) {
        List<IndexedFileUpdate> updates = new ArrayList<>(count);
        int symbolsPerFile = Math.max(1, REINDEX_SYMBOLS / fileCount);
        for (int file = 0; file < count; file++) {
            String relativePath = filePath(file);
            List<CodeSymbol> symbols = new ArrayList<>(symbolsPerFile);
            List<SymbolRelation> relations = new ArrayList<>(symbolsPerFile);
            for (int offset = 0; offset < symbolsPerFile; offset++) {
                int symbol = file + offset * fileCount;
                String name = String.format(Locale.ROOT, "BenchSymbol%08d", symbol);
                symbols.add(new CodeSymbol(SymbolKind.CLASS, name, "bench.generated." + name,
                        "class " + name, 1, 2));
                relations.add(new SymbolRelation(RelationKind.USES, "bench.generated." + name,
                        "bench.target.Target" + symbol));
            }
            Path absolute = temporaryDirectory.resolve("reindex-sources").resolve(relativePath);
            updates.add(new IndexedFileUpdate(
                    new ScannedFile(absolute, relativePath, "java", 128,
                            "reindexed-hash-" + generation + "-" + file,
                            Instant.EPOCH.plusSeconds(1_000L + generation), 32, FileCategory.SOURCE),
                    new AnalysisResult(absolute, "java", symbols, relations)));
        }
        return updates;
    }

    private Map<String, Object> benchmarkPortfolio(List<Integer> tiers) throws Exception {
        Path home = temporaryDirectory.resolve("portfolio-home");
        try (NexusApplication application = NexusApplication.create(new NexusPaths(home))) {
            return benchmarkPortfolio(application, tiers);
        }
    }

    private Map<String, Object> benchmarkPortfolio(NexusApplication application, List<Integer> tiers) throws Exception {
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
            Measurement context = measure(() -> application.contextAcrossProjects(
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
                } while (!Thread.currentThread().isInterrupted()
                        && (!writerDone.get() || durations.size() + failures < 20));
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

    /**
     * Le harnais s'exécute aussi sur le code de base de la PR (voir {@code scale-benchmark.yml}) : le
     * type {@code SearchDocument} y est résolu par réflexion, dans le package où le code mesuré le déclare.
     */
    private static Class<?> searchDocumentType() {
        for (String name : List.of("com.nexus.index.SearchDocument", "com.nexus.search.SearchDocument")) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // Le package suivant est essayé.
            }
        }
        throw new IllegalStateException("SearchDocument est introuvable");
    }

    private static Object searchDocument(String relativePath, String content) throws ReflectiveOperationException {
        return searchDocumentType()
                .getDeclaredConstructor(String.class, String.class, FileCategory.class, String.class, List.class)
                .newInstance(relativePath, "java", FileCategory.SOURCE, content, List.of());
    }

    /** Appelle {@code rebuild} par réflexion : la signature porte un type que le harnais ne nomme pas. */
    private static void rebuild(SemanticIndexingService service, UUID projectId, String fingerprint,
                                List<Object> documents) throws Exception {
        try {
            SemanticIndexingService.class
                    .getMethod("rebuild", UUID.class, String.class, List.class)
                    .invoke(service, projectId, fingerprint, documents);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private Map<String, Object> benchmarkSemanticRecovery(int documentCount) throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("semantic-home"));
        DeterministicEmbeddingProvider provider = new DeterministicEmbeddingProvider(SEMANTIC_DIMENSIONS);
        LuceneSemanticSearchIndex index = new LuceneSemanticSearchIndex(paths, SEMANTIC_DIMENSIONS);
        SemanticIndexingService service = new SemanticIndexingService(provider, index, 2_000, 128);
        UUID projectId = UUID.randomUUID();
        List<Object> documents = new ArrayList<>(documentCount);
        for (int indexNumber = 0; indexNumber < documentCount; indexNumber++) {
            documents.add(searchDocument(
                    "src/generated/Doc" + indexNumber + ".java",
                    "synthetic semantic scale document " + indexNumber + " shared recovery context"));
        }

        String firstFingerprint = "synthetic-semantic-fingerprint-v1";
        long firstStarted = System.nanoTime();
        rebuild(service, projectId, firstFingerprint, documents);
        long firstMs = elapsedMillis(firstStarted);
        assertTrue(service.isCompatible(projectId, firstFingerprint));

        String secondFingerprint = "synthetic-semantic-fingerprint-v2";
        assertFalse(service.isCompatible(projectId, secondFingerprint));
        long recoveryStarted = System.nanoTime();
        rebuild(service, projectId, secondFingerprint, documents);
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

                try (var symbols = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS)) {
                    for (int symbol = 0; symbol < symbolCount; symbol++) {
                        long fileId = fileIds.get(symbol % fileIds.size());
                        String name = symbol % 10_000 == 0
                                ? String.format(Locale.ROOT, "ScaleNeedle%08d", symbol)
                                : String.format(Locale.ROOT, "BenchSymbol%08d", symbol);
                        String qualified = "bench.generated." + name;
                        symbols.add(fileId, "CLASS", name, qualified, "class " + name, 1, 2, "javaparser");
                        if ((symbol + 1) % BATCH_SIZE == 0) {
                            symbols.flush();
                        }
                        if ((symbol + 1) % 50_000 == 0) {
                            symbols.flush();
                            connection.commit();
                        }
                    }
                    symbols.flush();
                    connection.commit();
                }

                if (relationCount > 0) {
                    try (var relations = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.RELATIONS)) {
                        for (int relation = 0; relation < relationCount; relation++) {
                            long fileId = fileIds.get(relation % fileIds.size());
                            String source = "bench.generated.BenchSymbol" + String.format(Locale.ROOT, "%08d", relation);
                            String target = relation % 10_000 == 0
                                    ? "bench.target.TargetNeedle" + relation
                                    : "bench.target.Target" + relation;
                            relations.add(projectId.toString(), fileId, "USES", source, target, 1.0, "javaparser");
                            if ((relation + 1) % BATCH_SIZE == 0) {
                                relations.flush();
                            }
                            if ((relation + 1) % 50_000 == 0) {
                                relations.flush();
                                connection.commit();
                            }
                        }
                        relations.flush();
                        connection.commit();
                    }
                }

                try (PreparedStatement generation = connection.prepareStatement("""
                        UPDATE project_index_generations
                        SET generation = generation + 1
                        WHERE project_id = ?
                        """)) {
                    generation.setString(1, projectId.toString());
                    generation.executeUpdate();
                }
                connection.commit();
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

    private record Measurement(long p50Micros, long p95Micros, long maxMicros, long meanMicros, List<Long> samplesMicros) {
        private static Measurement fromMicros(List<Long> micros) {
            long mean = micros.isEmpty()
                    ? 0L
                    : Math.round(micros.stream().mapToLong(Long::longValue).average().orElse(0.0d));
            return new Measurement(
                    percentile(micros, 0.50d),
                    percentile(micros, 0.95d),
                    micros.stream().mapToLong(Long::longValue).max().orElse(0L),
                    mean,
                    List.copyOf(micros));
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("p50Micros", p50Micros);
            result.put("p95Micros", p95Micros);
            result.put("p50Ms", p50Micros / 1_000.0d);
            result.put("p95Ms", p95Micros / 1_000.0d);
            result.put("maxMs", maxMicros / 1_000.0d);
            result.put("meanMs", meanMicros / 1_000.0d);
            result.put("samplesMicros", samplesMicros);
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
