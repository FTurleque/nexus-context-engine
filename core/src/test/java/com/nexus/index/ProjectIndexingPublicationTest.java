package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.index.service.ProjectIndexingService;
import com.nexus.index.service.ProjectIndexingServices;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.ContextRanker;
import com.nexus.search.SearchService;
import com.nexus.search.SearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticIndexingService;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rétention bornée des analyses et writers Lucene par indexation (AUD-PRF-12, AUD-PRF-11) : les
 * analyses sont mises en attente au rythme des lots, rien n'est visible avant la publication et un
 * échec laisse la génération précédente intacte. Un échec à la barrière de publication, après le
 * commit des index dérivés, laisse le projet {@code FAILED} et le passage suivant le reconstruit
 * entièrement (AUD-TST-16).
 */
class ProjectIndexingPublicationTest {

    private static final int BATCH_FILES = 5;
    private static final long BATCH_BYTES = 16L * 1024L * 1024L;
    private static final long AWAIT_SECONDS = 60;
    private static final String PUBLISH_FAILURE = "échec de publication canonique injecté";
    private static final String SEMANTIC_COMMIT_FAILURE = "échec du commit sémantique injecté";

    @TempDir
    Path temporaryDirectory;

    @Test
    void analysesAreStagedAtTheBatchPaceAndPublishedOnce() throws Exception {
        Path projectRoot = writeDocuments("project", 23, "marker");
        Env env = env();
        List<Integer> stagedSizes = new ArrayList<>();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        IndexRepository recording = recording(env.indexRepository(), stagedSizes, events);
        ProjectDescriptor project = env.registry().register(projectRoot, "staged");

        ProjectIndexingService service = service(env, recording, new FlakyAnalyzer(file -> { }), SemanticIndexing.disabled());
        IndexingReport report = service.index(project.id());

        assertEquals(23, report.changedFiles());
        assertEquals(23, stagedSizes.stream().mapToInt(Integer::intValue).sum());
        assertTrue(stagedSizes.size() >= 23 / BATCH_FILES, stagedSizes.toString());
        assertTrue(stagedSizes.stream().allMatch(size -> size <= BATCH_FILES),
                "chaque lot mis en attente est borné par le lot de publication : " + stagedSizes);
        assertEquals(1, events.stream().filter("publish"::equals).count(), events.toString());
        assertEquals("publish", events.getLast(), "rien n'est publié avant la fin des lots");
        assertEquals(new IndexStatistics(23, 0, 0), report.statistics());
        assertEquals(1L, env.indexRepository().generation(project.id()));
    }

    @Test
    void indexingMoreThanOneDefaultBatchCommitsEachLuceneIndexOnce() throws Exception {
        int files = ProjectIndexingService.DEFAULT_INDEX_DOCUMENT_BATCH_FILES * 2 + 44;
        Path projectRoot = writeDocuments("project", files, "marker");
        Env env = env();
        ProjectDescriptor project = env.registry().register(projectRoot, "single-commit");
        SemanticIndexingService semantic = new SemanticIndexingService(
                new ConstantEmbeddingProvider(), new LuceneSemanticSearchIndex(env.paths(), 3));
        ProjectIndexingService service = ProjectIndexingServices.withSemantic(
                env.projectRepository(), env.indexRepository(), new ProjectScanner(),
                List.of(new FlakyAnalyzer(file -> { })), new LuceneSearchIndex(env.paths()),
                List.of(), List.of(), semantic);

        IndexingReport report = service.index(project.id());

        assertEquals(files, report.changedFiles());
        assertEquals(files, luceneDocumentCount(env.paths(), project.id()));
        assertEquals(1L, commitGeneration(env.paths().projectLuceneIndex(project.id())),
                "un seul commit lexical pour plus de 128 fichiers");
        assertEquals(1L, commitGeneration(env.paths().projectSemanticLuceneIndex(project.id())),
                "un seul commit sémantique pour plus de 128 fichiers");
    }

    @Test
    void aFailureAfterSeveralBatchesLeavesThePreviousGenerationAndDerivedIndexesIntact() throws Exception {
        Path projectRoot = writeDocuments("project", 20, "oldmarker");
        Env env = env();
        ProjectDescriptor project = env.registry().register(projectRoot, "failing");
        FlakyAnalyzer analyzer = new FlakyAnalyzer(file -> { });
        SemanticIndexingService semantic = new SemanticIndexingService(
                new ConstantEmbeddingProvider(), new LuceneSemanticSearchIndex(env.paths(), 3));
        ProjectIndexingService service = service(env, env.indexRepository(), analyzer, semantic);

        service.index(project.id());
        long generation = env.indexRepository().generation(project.id());
        Map<String, IndexedFile> filesBefore = env.indexRepository().findFiles(project.id());
        String oldFingerprint = CanonicalIndexFingerprint.fromScannedFiles(
                new ProjectScanner().scanWithDiagnostics(projectRoot).files());
        assertTrue(semantic.isCompatible(project.id(), oldFingerprint));
        assertEquals(20, luceneDocumentCount(env.paths(), project.id()));

        writeDocuments("project", 20, "newmarker");
        analyzer.onAnalyze = file -> {
            if (file.getFileName().toString().equals("doc-14.md")) {
                throw new IllegalStateException("échec d'analyse injecté");
            }
        };
        assertThrows(IllegalStateException.class, () -> service.index(project.id()));

        assertEquals(IndexStatus.FAILED, env.registry().get(project.id()).indexStatus());
        assertEquals(generation, env.indexRepository().generation(project.id()));
        assertEquals(filesBefore, env.indexRepository().findFiles(project.id()),
                "aucun fait partiel n'est visible");
        assertEquals(0L, count(env.database(), "index_staged_files"));
        assertEquals(20, luceneDocumentCount(env.paths(), project.id()));
        LuceneSearchIndex lucene = new LuceneSearchIndex(env.paths());
        assertFalse(lucene.search(project.id(), "oldmarker", 5).isEmpty());
        assertTrue(lucene.search(project.id(), "newmarker", 5).isEmpty());
        assertTrue(semantic.isCompatible(project.id(), oldFingerprint),
                "l'index sémantique garde sa provenance précédente");

        // La reprise reconstruit entièrement et publie le nouveau contenu.
        analyzer.onAnalyze = file -> { };
        IndexingReport retry = service.index(project.id());
        assertTrue(retry.fullSearchRebuild());
        assertFalse(lucene.search(project.id(), "newmarker", 5).isEmpty());
        assertEquals(IndexStatus.READY, env.registry().get(project.id()).indexStatus());
    }

    @Test
    void aCanonicalPublishFailureAfterTheLuceneCommitLeavesTheProjectFailedUntilAFullRebuild() throws Exception {
        Path projectRoot = writeDocuments("project", 20, "oldmarker");
        Env env = env();
        ProjectDescriptor project = env.registry().register(projectRoot, "publish-failure");
        AtomicBoolean failPublish = new AtomicBoolean(false);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        IndexRepository repository = recording(env.indexRepository(), new ArrayList<>(), events, failPublish::get);
        ProjectIndexingService service = service(env, repository, new FlakyAnalyzer(file -> { }),
                SemanticIndexing.disabled());

        service.index(project.id());
        assertEquals(IndexStatus.READY, env.registry().get(project.id()).indexStatus());
        long generation = env.indexRepository().generation(project.id());
        Map<String, IndexedFile> filesBefore = env.indexRepository().findFiles(project.id());

        writeDocuments("project", 20, "newmarker");
        failPublish.set(true);
        events.clear();
        var projectId = project.id();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.index(projectId));

        assertEquals(PUBLISH_FAILURE, failure.getMessage(), "l'exception de publication est propagée");
        assertTrue(events.contains("publish"), "la publication canonique a été tentée : " + events);
        LuceneSearchIndex lucene = new LuceneSearchIndex(env.paths());
        assertFalse(lucene.search(project.id(), "newmarker", 5).isEmpty(),
                "le commit Lucene a eu lieu avant l'échec de publication");
        assertEquals(IndexStatus.FAILED, env.registry().get(project.id()).indexStatus());
        assertEquals(generation, env.indexRepository().generation(project.id()));
        assertEquals(filesBefore, env.indexRepository().findFiles(project.id()),
                "aucun fait de la publication annulée n'est visible");
        assertEquals(0L, count(env.database(), "index_staged_files"), "aucune analyse en attente ne subsiste");
        assertSearchRefusedWithoutReadingDerivedIndexes(env.registry().get(project.id()));

        // Le verrou est libéré : la même instance reprend, reconstruit tout et publie.
        failPublish.set(false);
        IndexingReport retry = service.index(project.id());
        assertTrue(retry.fullSearchRebuild(), "un projet FAILED est reconstruit entièrement");
        assertEquals(IndexStatus.READY, env.registry().get(project.id()).indexStatus());
        assertEquals(generation + 1, env.indexRepository().generation(project.id()));
        assertEquals(20, luceneDocumentCount(env.paths(), project.id()));
        assertFalse(lucene.search(project.id(), "newmarker", 5).isEmpty());
        assertTrue(lucene.search(project.id(), "oldmarker", 5).isEmpty());
        assertEquals(0L, count(env.database(), "index_staged_files"));
    }

    @Test
    void aSemanticCommitFailureAfterTheLexicalCommitSkipsThePublicationUntilAFullRebuild() throws Exception {
        Path projectRoot = writeDocuments("project", 20, "oldmarker");
        Env env = env();
        ProjectDescriptor project = env.registry().register(projectRoot, "semantic-commit-failure");
        AtomicBoolean failSemanticCommit = new AtomicBoolean(false);
        SemanticIndexingService delegate = new SemanticIndexingService(
                new ConstantEmbeddingProvider(), new LuceneSemanticSearchIndex(env.paths(), 3));
        SemanticIndexing semantic = failingSemanticCommit(delegate, failSemanticCommit::get);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        IndexRepository repository = recording(env.indexRepository(), new ArrayList<>(), events, () -> false);
        ProjectIndexingService service = service(env, repository, new FlakyAnalyzer(file -> { }), semantic);

        service.index(project.id());
        long generation = env.indexRepository().generation(project.id());
        Map<String, IndexedFile> filesBefore = env.indexRepository().findFiles(project.id());
        String oldFingerprint = CanonicalIndexFingerprint.fromScannedFiles(
                new ProjectScanner().scanWithDiagnostics(projectRoot).files());
        assertTrue(delegate.isCompatible(project.id(), oldFingerprint));

        writeDocuments("project", 20, "newmarker");
        failSemanticCommit.set(true);
        events.clear();
        IOException failure = assertThrows(IOException.class, () -> service.index(project.id()));

        assertEquals(SEMANTIC_COMMIT_FAILURE, failure.getMessage(), "l'exception du commit sémantique est propagée");
        assertFalse(events.contains("publish"), "la publication canonique n'a pas lieu : " + events);
        LuceneSearchIndex lucene = new LuceneSearchIndex(env.paths());
        assertFalse(lucene.search(project.id(), "newmarker", 5).isEmpty(),
                "le commit lexical a eu lieu avant l'échec du commit sémantique");
        assertEquals(IndexStatus.FAILED, env.registry().get(project.id()).indexStatus());
        assertEquals(generation, env.indexRepository().generation(project.id()));
        assertEquals(filesBefore, env.indexRepository().findFiles(project.id()));
        assertEquals(0L, count(env.database(), "index_staged_files"), "aucune analyse en attente ne subsiste");
        assertTrue(delegate.isCompatible(project.id(), oldFingerprint),
                "l'index sémantique garde sa provenance précédente");
        assertSearchRefusedWithoutReadingDerivedIndexes(env.registry().get(project.id()));

        failSemanticCommit.set(false);
        IndexingReport retry = service.index(project.id());
        String newFingerprint = CanonicalIndexFingerprint.fromScannedFiles(
                new ProjectScanner().scanWithDiagnostics(projectRoot).files());
        assertTrue(retry.fullSearchRebuild(), "un projet FAILED est reconstruit entièrement");
        assertEquals(IndexStatus.READY, env.registry().get(project.id()).indexStatus());
        assertEquals(generation + 1, env.indexRepository().generation(project.id()));
        assertTrue(delegate.isCompatible(project.id(), newFingerprint),
                "l'index sémantique suit la génération publiée");
        assertFalse(lucene.search(project.id(), "newmarker", 5).isEmpty());
    }

    @Test
    void anotherProjectCanWriteWhileAnIndexingIsInProgressBetweenBatches() throws Exception {
        Path projectRoot = writeDocuments("project", 20, "marker");
        Path otherRoot = writeDocuments("other", 1, "other");
        Env env = env();
        ProjectDescriptor project = env.registry().register(projectRoot, "long-indexing");
        ProjectDescriptor other = env.registry().register(otherRoot, "other");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FlakyAnalyzer analyzer = new FlakyAnalyzer(file -> {
            if (file.getFileName().toString().equals("doc-15.md")) {
                reached.countDown();
                await(release);
            }
        });
        ProjectIndexingService service = service(env, env.indexRepository(), analyzer, SemanticIndexing.disabled());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<IndexingReport> indexing = executor.submit(() -> service.index(project.id()));
            assertTrue(reached.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            // Des analyses sont déjà en attente : l'indexation a écrit au rythme des lots...
            assertTrue(count(env.database(), "index_staged_files") > 0);
            // ... mais ne tient aucune transaction d'écriture ouverte : un autre projet écrit sans
            // attendre le busy_timeout, ni échouer en SQLITE_BUSY.
            long started = System.nanoTime();
            new IndexedFileWriter(env.indexRepository()).writeOneFile(other.id());
            ProjectDescriptor renamed = new ProjectDescriptor(other.id(), "other-renamed", other.rootPath(),
                    other.sourceType(), other.languages(), other.technologies(), other.lastIndexedAt(),
                    other.indexStatus());
            env.projectRepository().save(renamed);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 4_000, "écriture concurrente : " + elapsedMillis + " ms");
            assertEquals(0L, env.indexRepository().generation(project.id()),
                    "la génération du projet indexé n'est pas publiée avant la fin");
            assertEquals(1, env.indexRepository().findFiles(other.id()).size());

            release.countDown();
            assertEquals(20, indexing.get(AWAIT_SECONDS, TimeUnit.SECONDS).changedFiles());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1L, env.indexRepository().generation(project.id()));
        assertEquals(0L, count(env.database(), "index_staged_files"));
    }

    /** Écrit un fichier dans un autre projet par le chemin d'écriture de l'indexation. */
    private record IndexedFileWriter(IndexRepository repository) {
        void writeOneFile(UUID projectId) {
            Path absolute = Path.of("other.md").toAbsolutePath();
            ScannedFile file = new ScannedFile(absolute, "other.md", "MARKDOWN", 5, "hash-other",
                    java.time.Instant.EPOCH, 1, FileCategory.DOCUMENTATION);
            IndexRepositoryWrites.apply(repository, projectId,
                    List.of(new IndexedFileUpdate(file,
                            new AnalysisResult(absolute, "MARKDOWN", List.of(), List.of()))),
                    Set.of());
        }
    }

    private ProjectIndexingService service(
            Env env,
            IndexRepository repository,
            LanguageAnalyzer analyzer,
            SemanticIndexing semantic) {
        return ProjectIndexingServices.withBatching(
                env.projectRepository(),
                repository,
                new ProjectScanner(),
                List.of(analyzer),
                new LuceneSearchIndex(env.paths()),
                List.of(),
                List.of(),
                semantic,
                Duration.ofSeconds(5),
                ProjectIndexLockManager.processLocalOnly(),
                BATCH_FILES,
                BATCH_BYTES);
    }

    private Env env() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        return new Env(paths, database, projectRepository, new SqliteIndexRepository(database),
                new ProjectRegistry(projectRepository));
    }

    private record Env(
            NexusPaths paths,
            SqliteDatabase database,
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectRegistry registry) { }

    private Path writeDocuments(String directory, int count, String marker) throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve(directory));
        for (int index = 0; index < count; index++) {
            Files.writeString(root.resolve(String.format(Locale.ROOT, "doc-%02d.md", index)),
                    "# " + marker + " " + index + "\n");
        }
        return root;
    }

    private static IndexRepository recording(IndexRepository delegate, List<Integer> stagedSizes,
            List<String> events) {
        return recording(delegate, stagedSizes, events, () -> false);
    }

    /** Enregistre les lots et la publication ; {@code failPublish} fait lever {@code publish}. */
    private static IndexRepository recording(IndexRepository delegate, List<Integer> stagedSizes,
            List<String> events, BooleanSupplier failPublish) {
        return (IndexRepository) Proxy.newProxyInstance(
                ProjectIndexingPublicationTest.class.getClassLoader(),
                new Class<?>[] {IndexRepository.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("openWriteSession")) {
                            return recordingSession((IndexWriteSession) result, stagedSizes, events, failPublish);
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static IndexWriteSession recordingSession(IndexWriteSession delegate, List<Integer> stagedSizes,
            List<String> events, BooleanSupplier failPublish) {
        return new IndexWriteSession() {
            @Override
            public void stage(List<IndexedFileUpdate> updates) {
                stagedSizes.add(updates.size());
                events.add("stage");
                delegate.stage(updates);
            }

            @Override
            public void publish(Set<String> removedPaths) {
                events.add("publish");
                if (failPublish.getAsBoolean()) {
                    throw new IllegalStateException(PUBLISH_FAILURE);
                }
                delegate.publish(removedPaths);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    /** Délègue l'indexation sémantique ; {@code failCommit} fait lever {@code commit} de la session. */
    private static SemanticIndexing failingSemanticCommit(SemanticIndexing delegate, BooleanSupplier failCommit) {
        return new SemanticIndexing() {
            @Override
            public boolean enabled() {
                return delegate.enabled();
            }

            @Override
            public boolean isCompatible(UUID projectId, String canonicalFingerprint) throws IOException {
                return delegate.isCompatible(projectId, canonicalFingerprint);
            }

            @Override
            public Session openSession(UUID projectId, String canonicalFingerprint, boolean rebuild)
                    throws IOException {
                return new FailingCommitSession(
                        delegate.openSession(projectId, canonicalFingerprint, rebuild), failCommit);
            }
        };
    }

    /** Session sémantique dont {@code commit} lève tant que {@code failCommit} est vrai. */
    private record FailingCommitSession(SemanticIndexing.Session session, BooleanSupplier failCommit)
            implements SemanticIndexing.Session {
        @Override
        public void applyChanges(List<SearchDocument> documents, Set<String> removedRelativePaths)
                throws IOException {
            session.applyChanges(documents, removedRelativePaths);
        }

        @Override
        public void commit() throws IOException {
            if (failCommit.getAsBoolean()) {
                throw new IOException(SEMANTIC_COMMIT_FAILURE);
            }
            session.commit();
        }

        @Override
        public void close() throws IOException {
            session.close();
        }
    }

    /**
     * La garde de disponibilité refuse la recherche sur un projet non {@code READY} avant toute
     * stratégie, donc sans lire les index dérivés.
     */
    private static void assertSearchRefusedWithoutReadingDerivedIndexes(ProjectDescriptor project) {
        AtomicInteger strategyCalls = new AtomicInteger();
        SearchStrategy strategy = (searched, query, limit) -> {
            strategyCalls.incrementAndGet();
            return List.of();
        };
        ContextRanker ranker = (request, candidates) -> List.of();
        SearchService search = new SearchService(List.of(strategy), List.of(), ranker);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> search.search(project, "newmarker", 5, false));
        assertTrue(refusal.getMessage().contains("n'est pas READY"), refusal.getMessage());
        assertEquals(0, strategyCalls.get(), "aucune stratégie ne lit les index dérivés");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("délai d'attente dépassé");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static long count(SqliteDatabase database, String table) throws Exception {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static long commitGeneration(Path indexPath) throws Exception {
        try (Directory directory = FSDirectory.open(indexPath);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.getIndexCommit().getGeneration();
        }
    }

    private static int luceneDocumentCount(NexusPaths paths, UUID projectId) throws Exception {
        try (Directory directory = FSDirectory.open(paths.projectLuceneIndex(projectId));
                DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    /** Analyseur Markdown de test avec un crochet exécuté avant chaque analyse. */
    private static final class FlakyAnalyzer implements LanguageAnalyzer {
        private volatile Consumer<Path> onAnalyze;

        private FlakyAnalyzer(Consumer<Path> onAnalyze) {
            this.onAnalyze = onAnalyze;
        }

        @Override
        public boolean supports(Path file) {
            return file.getFileName().toString().endsWith(".md");
        }

        @Override
        public AnalysisResult analyze(Path projectRoot, Path file) throws IOException {
            onAnalyze.accept(file);
            return new AnalysisResult(file, "MARKDOWN", List.of(), List.of());
        }
    }

    private static final class ConstantEmbeddingProvider implements EmbeddingProvider {
        @Override
        public String modelId() {
            return "constant-test-model";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public float[] embed(String text) {
            return new float[] {1.0f, 0.0f, 0.0f};
        }
    }
}
