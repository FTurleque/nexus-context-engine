package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.index.IndexRepository;
import io.github.fturleque.nexus.index.ProjectIndexingService;
import io.github.fturleque.nexus.index.java.JavaParserLanguageAnalyzer;
import io.github.fturleque.nexus.index.scan.ProjectScanner;
import io.github.fturleque.nexus.persistence.sqlite.SqliteDatabase;
import io.github.fturleque.nexus.persistence.sqlite.SqliteIndexRepository;
import io.github.fturleque.nexus.persistence.sqlite.SqliteProjectRepository;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRegistry;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.ranking.DeterministicContextRanker;
import io.github.fturleque.nexus.ranking.graph.GraphCandidateEnricher;
import io.github.fturleque.nexus.search.CandidateType;
import io.github.fturleque.nexus.search.SearchIndex;
import io.github.fturleque.nexus.search.SearchService;
import io.github.fturleque.nexus.search.SymbolSearchStrategy;
import io.github.fturleque.nexus.search.lucene.LuceneFileSearchStrategy;
import io.github.fturleque.nexus.search.lucene.LuceneSearchIndex;
import io.github.fturleque.nexus.token.HeuristicTokenEstimator;
import io.github.fturleque.nexus.token.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextBuilderIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsDeterministicSymbolFirstContextWithinBudget() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        writeJava(projectRoot, "demo/OrderRepository.java", """
                package demo;
                interface OrderRepository {
                    void save(String orderId);
                }
                """);
        writeJava(projectRoot, "demo/OrderService.java", """
                package demo;
                import demo.OrderRepository;
                class OrderService {
                    private final OrderRepository repository;
                    OrderService(OrderRepository repository) { this.repository = repository; }
                    void create(String orderId) { repository.save(orderId); }
                }
                """);

        Fixture fixture = fixture(projectRoot, "orders");
        fixture.indexingService().index(fixture.project().id());

        ContextRequest request = new ContextRequest(
                fixture.project().id(),
                "OrderService create order",
                120,
                Set.of(CandidateType.FILE, CandidateType.SYMBOL),
                Map.of(),
                true);
        ContextBundle first = fixture.contextBuilder().build(request);
        ContextBundle second = fixture.contextBuilder().build(request);

        assertFalse(first.items().isEmpty());
        assertTrue(first.estimatedTokens() <= first.tokenBudget());
        assertTrue(first.items().stream().allMatch(item -> !item.path().isAbsolute()));
        assertTrue(first.items().stream().anyMatch(item ->
                item.path().toString().replace('\\', '/').endsWith("demo/OrderService.java")));
        assertEquals(first.items(), second.items());
        assertEquals(first.estimatedTokens(), second.estimatedTokens());
        assertTrue(((Number) first.metadata().get("reductionRatio")).doubleValue() >= 0.0d);
        assertTrue(((Number) first.metadata().get("selectedEstimatedTokens")).intValue() <= 120);
    }

    @Test
    void truncatesLargeRelevantFragmentWithoutExceedingBudget() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("large-project"));
        StringBuilder source = new StringBuilder("package demo;\nclass LargeService {\n");
        for (int index = 0; index < 80; index++) {
            source.append("    void step").append(index).append("() { System.out.println(\"")
                    .append(index).append("\"); }\n");
        }
        source.append("}\n");
        writeJava(projectRoot, "demo/LargeService.java", source.toString());

        Fixture fixture = fixture(projectRoot, "large");
        fixture.indexingService().index(fixture.project().id());

        ContextBundle bundle = fixture.contextBuilder().build(new ContextRequest(
                fixture.project().id(),
                "LargeService",
                60,
                Set.of(),
                Map.of(),
                true));

        assertFalse(bundle.items().isEmpty());
        assertTrue(bundle.estimatedTokens() <= 60);
        assertTrue(bundle.items().stream().anyMatch(ContextItem::truncated));
        assertTrue(((Number) bundle.metadata().get("truncatedItems")).intValue() >= 1);
    }

    private Fixture fixture(Path projectRoot, String name) throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home-" + name));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, name);
        SearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                searchIndex);
        SearchService searchService = new SearchService(
                List.of(
                        new LuceneFileSearchStrategy(searchIndex),
                        new SymbolSearchStrategy(indexRepository)),
                new GraphCandidateEnricher(indexRepository),
                new DeterministicContextRanker());
        TokenEstimator tokenEstimator = new HeuristicTokenEstimator();
        ContextBuilder contextBuilder = new DefaultContextBuilder(
                projectRepository,
                searchService,
                new ContextFragmentFactory(tokenEstimator),
                new FragmentMerger(),
                new BudgetedContextSelector(tokenEstimator),
                tokenEstimator);
        return new Fixture(project, indexingService, contextBuilder);
    }

    private static void writeJava(Path projectRoot, String relativePath, String content) throws Exception {
        Path source = projectRoot.resolve("src/main/java").resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private record Fixture(
            ProjectDescriptor project,
            ProjectIndexingService indexingService,
            ContextBuilder contextBuilder) {
    }
}
