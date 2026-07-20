package com.nexus.api;

import com.nexus.config.NexusPaths;
import com.nexus.context.BudgetedContextSelector;
import com.nexus.context.ContextBuilder;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextFragmentFactory;
import com.nexus.context.ContextRequest;
import com.nexus.context.DefaultContextBuilder;
import com.nexus.context.FragmentMerger;
import com.nexus.context.source.git.GitRecencyCandidateEnricher;
import com.nexus.context.source.git.LocalGitContextSourceProvider;
import com.nexus.context.source.instruction.AgentsMdInstructionProvider;
import com.nexus.context.source.instruction.ClaudeInstructionProvider;
import com.nexus.context.source.instruction.CopilotInstructionProvider;
import com.nexus.context.source.instruction.GeminiInstructionProvider;
import com.nexus.context.source.skill.LocalAgentSkillsProvider;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexingReport;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.index.scip.ScipCodeIndexImporter;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.RankedCandidate;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchService;
import com.nexus.search.SymbolSearchStrategy;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class NexusApiApplicationService {

    private final MeterRegistry meterRegistry;
    private final NexusPaths paths;
    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectRegistry projectRegistry;
    private final ProjectIndexingService indexingService;
    private final SearchService searchService;
    private final ContextBuilder contextBuilder;

    public NexusApiApplicationService(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.paths = NexusPaths.fromEnvironment();

        SqliteDatabase database = initializeDatabase(paths);
        this.projectRepository = new SqliteProjectRepository(database);
        this.indexRepository = new SqliteIndexRepository(database);
        this.projectRegistry = new ProjectRegistry(projectRepository);

        SearchIndex searchIndex = new LuceneSearchIndex(paths);
        List<CodeIntelligenceProvider> codeIntelligenceProviders =
                JdtLanguageServerCodeIntelligenceProvider.fromEnvironment(paths)
                        .<List<CodeIntelligenceProvider>>map(List::of)
                        .orElseGet(List::of);

        this.indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(
                        new JavaParserLanguageAnalyzer(),
                        new MarkdownLanguageAnalyzer()),
                searchIndex,
                List.of(new ScipCodeIndexImporter()),
                codeIntelligenceProviders);

        this.searchService = new SearchService(
                List.of(
                        new LuceneFileSearchStrategy(searchIndex),
                        new SymbolSearchStrategy(indexRepository)),
                List.of(
                        new GraphCandidateEnricher(indexRepository),
                        new GitRecencyCandidateEnricher()),
                new DeterministicContextRanker());

        TokenEstimator tokenEstimator = new HeuristicTokenEstimator();
        this.contextBuilder = new DefaultContextBuilder(
                projectRepository,
                searchService,
                new ContextFragmentFactory(tokenEstimator),
                new FragmentMerger(),
                new BudgetedContextSelector(tokenEstimator),
                tokenEstimator,
                List.of(
                        new AgentsMdInstructionProvider(),
                        new CopilotInstructionProvider(),
                        new ClaudeInstructionProvider(),
                        new GeminiInstructionProvider()),
                List.of(new LocalAgentSkillsProvider()),
                new LocalGitContextSourceProvider());
    }

    public NexusPaths paths() {
        return paths;
    }

    public List<ProjectDescriptor> listProjects() {
        return projectRegistry.list();
    }

    public ProjectDescriptor registerProject(Path rootPath, String name) throws IOException {
        return projectRegistry.register(rootPath, name);
    }

    public ProjectDescriptor getProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));
    }

    public IndexOperation index(UUID projectId, boolean rebuild, boolean deepJava) throws IOException {
        ProjectDescriptor project = getProject(projectId);
        long startedAt = System.nanoTime();
        meterRegistry.counter("nexus.api.operations", "operation", "index").increment();
        try {
            IndexingReport report;
            if (deepJava) {
                report = rebuild
                        ? indexingService.rebuildWithCodeIntelligence(projectId)
                        : indexingService.indexWithCodeIntelligence(projectId);
            } else {
                report = rebuild
                        ? indexingService.rebuild(projectId)
                        : indexingService.index(projectId);
            }
            ProjectDescriptor updated = projectRepository.findById(projectId).orElse(project);
            return new IndexOperation(updated, report);
        } finally {
            recordDuration("index", startedAt);
        }
    }

    public IndexStatistics inspect(UUID projectId) {
        getProject(projectId);
        return indexRepository.statistics(projectId);
    }

    public SearchOperation search(UUID projectId, String query, int limit, boolean explain) throws IOException {
        ProjectDescriptor project = getProject(projectId);
        long startedAt = System.nanoTime();
        meterRegistry.counter("nexus.api.operations", "operation", "search").increment();
        try {
            List<RankedCandidate> results = searchService.search(project, query, limit, explain);
            return new SearchOperation(project, query, limit, explain, elapsedMillis(startedAt), results);
        } finally {
            recordDuration("search", startedAt);
        }
    }

    public ContextOperation context(
            UUID projectId,
            String query,
            int tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        ProjectDescriptor project = getProject(projectId);
        long startedAt = System.nanoTime();
        meterRegistry.counter("nexus.api.operations", "operation", "context").increment();
        try {
            ContextBundle bundle = contextBuilder.build(new ContextRequest(
                    projectId,
                    query,
                    tokenBudget,
                    parseRequestedSources(requestedSources),
                    constraints == null ? Map.of() : constraints,
                    explain));
            return new ContextOperation(project, query, explain, elapsedMillis(startedAt), bundle);
        } finally {
            recordDuration("context", startedAt);
        }
    }

    private static SqliteDatabase initializeDatabase(NexusPaths paths) {
        try {
            return new SqliteDatabase(paths);
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Impossible d'initialiser le stockage SQLite de NEXUS", exception);
        }
    }

    private Set<CandidateType> parseRequestedSources(Set<String> requestedSources) {
        if (requestedSources == null || requestedSources.isEmpty()) {
            return Set.of();
        }
        return requestedSources.stream()
                .map(value -> {
                    try {
                        return CandidateType.valueOf(value.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Source de contexte inconnue : " + value, exception);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private void recordDuration(String operation, long startedAt) {
        meterRegistry.timer("nexus.api.operation.duration", "operation", operation)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    public record IndexOperation(ProjectDescriptor project, IndexingReport report) {
    }

    public record SearchOperation(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) {
    }

    public record ContextOperation(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            ContextBundle bundle) {
    }
}
