import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.index.*;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.*;
import com.nexus.project.*;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.search.*;
import com.nexus.search.lucene.*;
import com.nexus.search.semantic.*;
import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import com.nexus.security.SensitiveContentRedactor;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Reproductions indépendantes, données synthétiques uniquement. Aucun correctif produit. */
public class AuditProbe {
    static final String SECRET = "AuditSyntheticPassword98765";
    static Path base;
    public static void main(String[] args) throws Exception {
        base = Files.createTempDirectory(Path.of(args[0]).toAbsolutePath().normalize(), "audit-run-");
        System.out.println("DATA=" + base);
        quotedJson();
        repeatedQuery();
        missingIndex();
        concurrentRead();
        ollamaTimeout();
    }
    static void quotedJson() throws Exception {
        String content = "{\"password\":\"" + SECRET + "\",\"feature\":\"auditneedle\"}";
        System.out.println("JSON_REDACTOR_LEAK=" + SensitiveContentRedactor.redact(content).contains(SECRET));
        Path root = Files.createDirectory(base.resolve("json-project"));
        Files.writeString(root.resolve("settings.js"), "export const settings = " + content + ";\n");
        List<String> sent = new ArrayList<>();
        EmbeddingProvider capture = new EmbeddingProvider() {
            public String modelId() { return "audit-capture"; }
            public int dimensions() { return 2; }
            public float[] embed(String text) { sent.add(text); return new float[]{1, 0}; }
        };
        try (NexusApplication app = NexusApplication.create(new NexusPaths(base.resolve("json-home")), SemanticSearchConfiguration.enabled(capture))) {
            var project = app.registerProject(root, "audit-json");
            app.index(project.id(), false, false);
            System.out.println("JSON_EMBEDDING_LEAK=" + sent.stream().anyMatch(s -> s.contains(SECRET)));
            var bundle = app.context(project.id(), "auditneedle", 2000, Set.of(CandidateType.FILE), Map.of(), true).bundle();
            System.out.println("JSON_CONTEXT_LEAK=" + bundle.items().stream().anyMatch(i -> i.content().contains(SECRET)));
        }
    }
    static void repeatedQuery() throws Exception {
        var index = new LuceneSearchIndex(new NexusPaths(base.resolve("repeat-home")));
        UUID id = UUID.randomUUID();
        index.rebuild(id, List.of(new SearchDocument("sample.js", "javascript", FileCategory.SOURCE, "alpha", List.of())));
        String query = QueryPolicy.normalize("alpha ".repeat(1100));
        try {
            System.out.println("REPEATED_QUERY_HITS=" + index.search(id, query, 10).size());
        } catch (Exception e) {
            System.out.println("REPEATED_QUERY_FAILURE=" + e.getClass().getName());
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) System.out.println("REPEATED_QUERY_CAUSE=" + c.getClass().getName());
        }
    }
    static void missingIndex() throws Exception {
        Path root = Files.createDirectory(base.resolve("missing-project"));
        Files.writeString(root.resolve("sample.js"), "export const feature = \"auditneedle\";\n");
        var paths = new NexusPaths(base.resolve("missing-home"));
        try (NexusApplication app = NexusApplication.create(paths, SemanticSearchConfiguration.disabled())) {
            var project = app.registerProject(root, "audit-missing");
            app.index(project.id(), false, false);
            System.out.println("MISSING_BEFORE=" + app.search(project.id(), "auditneedle", 10, false).results().size());
            Path generatedIndex = paths.projectLuceneIndex(project.id());
            if (!generatedIndex.toAbsolutePath().normalize().startsWith(base)) throw new IllegalStateException("Unsafe probe path");
            Files.move(generatedIndex, generatedIndex.resolveSibling("lucene-probe-backup"));
            var result = app.index(project.id(), false, false);
            System.out.println("MISSING_STATE=" + result.project().indexStatus() + ",CHANGED=" + result.report().changedFiles());
            System.out.println("MISSING_AFTER=" + app.search(project.id(), "auditneedle", 10, false).results().size());
            app.index(project.id(), true, false);
            System.out.println("MISSING_REBUILD=" + app.search(project.id(), "auditneedle", 10, false).results().size());
        }
    }
    static void concurrentRead() throws Exception {
        Path root = Files.createDirectory(base.resolve("race-project"));
        Files.writeString(root.resolve("sample.js"), "export const feature = \"auditneedle\";\n");
        var paths = new NexusPaths(base.resolve("race-home"));
        var db = new SqliteDatabase(paths);
        var projects = new SqliteProjectRepository(db);
        var records = new SqliteIndexRepository(db);
        var project = new ProjectRegistry(projects).register(root, "audit-race");
        var realIndex = new LuceneSearchIndex(paths);
        CountDownLatch emptied = new CountDownLatch(1), allowIndex = new CountDownLatch(1);
        SearchIndex gatedIndex = new SearchIndex() {
            public void rebuild(UUID id, List<SearchDocument> docs) throws java.io.IOException {
                realIndex.rebuild(id, docs);
                emptied.countDown();
                try { if (!allowIndex.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Probe gate timeout"); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
            }
            public void applyChanges(UUID id, List<SearchDocument> docs, Set<String> removed) throws java.io.IOException { realIndex.applyChanges(id, docs, removed); }
            public List<LexicalSearchHit> search(UUID id, String q, int n) throws java.io.IOException { return realIndex.search(id, q, n); }
        };
        new ProjectIndexingService(projects, records, new ProjectScanner(), List.of(), realIndex).index(project.id());
        ProjectDescriptor ready = projects.findById(project.id()).orElseThrow();
        CountDownLatch readEntered = new CountDownLatch(1), allowRead = new CountDownLatch(1);
        SearchStrategy gatedRead = (p, q, n) -> {
            readEntered.countDown();
            try { if (!allowRead.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Probe gate timeout"); }
            catch (InterruptedException e) { throw new java.io.IOException(e); }
            return new LuceneFileSearchStrategy(realIndex).search(p, q, n);
        };
        var searches = new SearchService(List.of(gatedRead), List.of(), new DeterministicContextRanker());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var reading = pool.submit(() -> searches.search(ready, "auditneedle", 10, false));
            if (!readEntered.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No read");
            var writing = pool.submit(() -> new ProjectIndexingService(projects, records, new ProjectScanner(), List.of(), gatedIndex,
                    List.of(), List.of(), null, ProjectIndexLockManager.fileBacked(paths)).rebuild(project.id()));
            try {
                if (!emptied.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No rebuild");
                allowRead.countDown();
                System.out.println("RACE_PERSISTED_STATE=" + projects.findById(project.id()).orElseThrow().indexStatus());
                System.out.println("RACE_SUCCESSFUL_READ_HITS=" + reading.get(10, TimeUnit.SECONDS).size());
            } finally { allowRead.countDown(); allowIndex.countDown(); }
            writing.get(10, TimeUnit.SECONDS);
        }
    }
    static void ollamaTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { Thread.sleep(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getResponseBody().write("\"embeddings\":[[1.0,0.0]]}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            var provider = new OllamaEmbeddingProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "audit", 2, Duration.ofMillis(500));
            long start = System.nanoTime();
            try { provider.embed("auditneedle"); System.out.println("OLLAMA_SUCCESS_MS=" + (System.nanoTime() - start) / 1_000_000); }
            catch (Exception e) { System.out.println("OLLAMA_FAILURE=" + e); }
            System.out.println("OLLAMA_CONFIGURED_TIMEOUT_MS=500");
        } finally { server.stop(0); }
    }
}
