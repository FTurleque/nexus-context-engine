package com.nexus.context;

import com.nexus.config.NexusPaths;
import com.nexus.context.source.git.GitContextQuery;
import com.nexus.context.source.git.GitContextResult;
import com.nexus.context.source.git.GitContextSourceProvider;
import com.nexus.index.IndexRepository;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchService;
import com.nexus.search.SymbolSearchStrategy;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void disablesGitBelowThresholdAndSelectsItWhenBudgetAllows() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("git-budget-project"));
        writeJava(projectRoot, "demo/OrderService.java", """
                package demo;
                class OrderService {
                    void create() {}
                }
                """);

        AtomicInteger providerCalls = new AtomicInteger();
        GitContextSourceProvider provider = new GitContextSourceProvider() {
            @Override
            public String id() {
                return "test-git";
            }

            @Override
            public GitContextResult discover(GitContextQuery query) {
                providerCalls.incrementAndGet();
                ContextFragment fragment = new ContextFragment(
                        CandidateType.GIT,
                        Path.of(".nexus/git/recent-commits.md"),
                        null,
                        1,
                        2,
                        "# Git recent context\n- recent order change\n",
                        0.8d,
                        Map.of("gitContextScore", 0.8d),
                        List.of("test Git context"));
                return new GitContextResult(List.of(fragment), true, true, 3, 1, 0, List.of());
            }
        };

        Fixture fixture = fixture(projectRoot, "git-budget", provider);
        fixture.indexingService().index(fixture.project().id());

        ContextBundle strict = fixture.contextBuilder().build(new ContextRequest(
                fixture.project().id(),
                "OrderService",
                180,
                Set.of(),
                Map.of(),
                true));
        assertEquals(0, providerCalls.get());
        assertFalse((Boolean) strict.metadata().get("gitEnabled"));
        assertTrue(strict.items().stream().noneMatch(item -> item.type() == CandidateType.GIT));

        ContextBundle comfortable = fixture.contextBuilder().build(new ContextRequest(
                fixture.project().id(),
                "OrderService",
                600,
                Set.of(),
                Map.of(),
                true));
        assertEquals(1, providerCalls.get());
        assertTrue((Boolean) comfortable.metadata().get("gitEnabled"));
        assertTrue(comfortable.estimatedTokens() <= 600);
        assertTrue(comfortable.items().stream().anyMatch(item -> item.type() == CandidateType.GIT));
        assertTrue(((Number) comfortable.metadata().get("gitSelectedItems")).intValue() >= 1);
    }

    private Fixture fixture(Path projectRoot, String name) throws Exception {
        return fixture(projectRoot, name, null);
    }

    private Fixture fixture(
            Path projectRoot,
            String name,
            GitContextSourceProvider gitContextProvider) throws Exception {
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
        ContextBuilder contextBuilder = gitContextProvider == null
                ? new DefaultContextBuilder(
                        projectRepository,
                        searchService,
                        new ContextFragmentFactory(tokenEstimator),
                        new FragmentMerger(),
                        new BudgetedContextSelector(tokenEstimator),
                        tokenEstimator)
                : new DefaultContextBuilder(
                        projectRepository,
                        searchService,
                        new ContextFragmentFactory(tokenEstimator),
                        new FragmentMerger(),
                        new BudgetedContextSelector(tokenEstimator),
                        tokenEstimator,
                        List.of(),
                        List.of(),
                        gitContextProvider);
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
