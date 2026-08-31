package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.context.BudgetedContextSelector;
import com.nexus.context.ContextBuilder;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextFragmentFactory;
import com.nexus.context.ContextRequest;
import com.nexus.context.DefaultContextBuilder;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextService;
import com.nexus.context.FragmentMerger;
import com.nexus.context.source.git.GitRecencyCandidateEnricher;
import com.nexus.context.source.git.LocalGitContextSourceProvider;
import com.nexus.context.source.instruction.AgentsMdInstructionProvider;
import com.nexus.context.source.instruction.ClaudeInstructionProvider;
import com.nexus.context.source.instruction.CopilotInstructionProvider;
import com.nexus.context.source.instruction.GeminiInstructionProvider;
import com.nexus.context.source.skill.AiSkillsRegistryProvider;
import com.nexus.context.source.skill.LocalAgentSkillsProvider;
import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.IndexingReport;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.SymbolRelation;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.minos.MinosCodeIndexImporter;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.index.scip.ScipCodeIndexImporter;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.ContextRanker;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.RankedCandidate;
import com.nexus.ranking.SemanticHybridContextRanker;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.FederatedSearchService;
import com.nexus.search.QueryPolicy;
import com.nexus.search.ResultLimitPolicy;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchService;
import com.nexus.search.SearchStrategy;
import com.nexus.search.SymbolSearchStrategy;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticIndexingService;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import com.nexus.search.semantic.SemanticSearchIndex;
import com.nexus.search.semantic.SemanticSearchStrategy;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Façade applicative indépendante des adaptateurs clients.
 *
 * <p>La CLI, REST et MCP doivent déléguer à cette façade afin de partager les
 * mêmes gates de cohérence, la même composition des providers et les mêmes
 * politiques de ranking/context.</p>
 */
public final class NexusApplication {

    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectRegistry projectRegistry;
    private final ProjectIndexingService indexingService;
    private final ProjectIndexLockManager projectIndexLockManager;
    private final SearchService searchService;
    private final FederatedSearchService federatedSearchService;
    private final ContextBuilder contextBuilder;
    private final FederatedContextService federatedContextService;
    private final boolean semanticSearchEnabled;

    private NexusApplication(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectRegistry projectRegistry,
            ProjectIndexingService indexingService,
            ProjectIndexLockManager projectIndexLockManager,
            SearchService searchService,
            FederatedSearchService federatedSearchService,
            ContextBuilder contextBuilder,
            FederatedContextService federatedContextService,
            boolean semanticSearchEnabled) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.indexingService = Objects.requireNonNull(indexingService, "indexingService");
        this.projectIndexLockManager = Objects.requireNonNull(projectIndexLockManager, "projectIndexLockManager");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.federatedSearchService = Objects.requireNonNull(federatedSearchService, "federatedSearchService");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.federatedContextService = Objects.requireNonNull(federatedContextService, "federatedContextService");
        this.semanticSearchEnabled = semanticSearchEnabled;
    }

    /** Compose NEXUS avec les opt-ins opérationnels explicitement présents dans l'environnement. */
    public static NexusApplication create(NexusPaths paths) throws SQLException, IOException {
        return create(paths, SemanticSearchConfiguration.fromEnvironment());
    }

    /** Compose NEXUS avec une configuration sémantique fournie explicitement par l'appelant. */
    public static NexusApplication create(
            NexusPaths paths,
            SemanticSearchConfiguration semanticSearchConfiguration) throws SQLException, IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(semanticSearchConfiguration, "semanticSearchConfiguration");

        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry projectRegistry = new ProjectRegistry(projectRepository);
        SearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexLockManager projectIndexLockManager = ProjectIndexLockManager.fileBacked(paths);

        List<CodeIntelligenceProvider> codeIntelligenceProviders =
                JdtLanguageServerCodeIntelligenceProvider.fromEnvironment(paths)
                        .<List<CodeIntelligenceProvider>>map(List::of)
                        .orElseGet(List::of);
        List<CodeIndexImporter> codeIndexImporters = List.of(new ScipCodeIndexImporter());

        List<SearchStrategy> searchStrategies = new ArrayList<>();
        searchStrategies.add(new LuceneFileSearchStrategy(searchIndex));
        searchStrategies.add(new SymbolSearchStrategy(indexRepository));

        SemanticIndexingService semanticIndexingService = null;
        if (semanticSearchConfiguration.enabled()) {
            EmbeddingProvider embeddingProvider = semanticSearchConfiguration.embeddingProvider()
                    .orElseThrow(() -> new IllegalStateException("Configuration sémantique activée sans provider"));
            SemanticSearchIndex semanticSearchIndex =
                    new LuceneSemanticSearchIndex(paths, embeddingProvider.dimensions());
            semanticIndexingService = new SemanticIndexingService(embeddingProvider, semanticSearchIndex);
            searchStrategies.add(new SemanticSearchStrategy(
                    embeddingProvider,
                    semanticSearchIndex,
                    indexRepository));
        }

        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer(), new MarkdownLanguageAnalyzer()),
                searchIndex,
                codeIndexImporters,
                codeIntelligenceProviders,
                semanticIndexingService,
                projectIndexLockManager);

        ContextRanker contextRanker = semanticSearchConfiguration.enabled()
                ? new SemanticHybridContextRanker(semanticSearchConfiguration.semanticRrfWeight())
                : new DeterministicContextRanker();
        SearchService searchService = new SearchService(
                searchStrategies,
                List.of(
                        new GraphCandidateEnricher(indexRepository),
                        new GitRecencyCandidateEnricher()),
                contextRanker);
        FederatedSearchService federatedSearchService = new FederatedSearchService(searchService);

        TokenEstimator tokenEstimator = new HeuristicTokenEstimator();
        ContextBuilder contextBuilder = new DefaultContextBuilder(
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
                List.of(
                        new LocalAgentSkillsProvider(),
                        new AiSkillsRegistryProvider()),
                new LocalGitContextSourceProvider());
        FederatedContextService federatedContextService = new FederatedContextService(contextBuilder);

        return new NexusApplication(
                projectRepository,
                indexRepository,
                projectRegistry,
                indexingService,
                projectIndexLockManager,
                searchService,
                federatedSearchService,
                contextBuilder,
                federatedContextService,
                semanticSearchConfiguration.enabled());
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

    public ProjectDescriptor resolveProject(String selector) {
        Objects.requireNonNull(selector, "selector");
        String normalized = selector.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Le sélecteur de projet ne peut pas être vide");
        }

        UUID projectId = null;
        try {
            projectId = UUID.fromString(normalized);
        } catch (IllegalArgumentException invalidUuid) {
            // Le sélecteur n'est pas un UUID : on tente alors seulement la résolution par nom.
        }
        if (projectId != null) {
            return getProject(projectId);
        }

        List<ProjectDescriptor> matches = projectRepository.findAll().stream()
                .filter(project -> project.name().equalsIgnoreCase(normalized))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Plusieurs projets portent le nom '" + normalized + "'. Utilisez leur UUID.");
        }
        throw new IllegalArgumentException("Projet introuvable : " + normalized);
    }

    public IndexOperation index(UUID projectId, boolean rebuild, boolean deepJava) throws IOException {
        ProjectDescriptor project = getProject(projectId);
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
        ProjectDescriptor updatedProject = projectRepository.findById(projectId).orElse(project);
        return new IndexOperation(updatedProject, report);
    }

    /** Le payload est fourni explicitement ; NEXUS ne lance jamais MINOS. */
    public CodeIntelligenceSnapshot importMinos(UUID projectId, String payload) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try (ProjectIndexLockManager.LockHandle ignored = projectIndexLockManager.acquire(projectId)) {
            ProjectDescriptor project = requireReadyProject(projectId);
            Set<String> indexedFiles = indexRepository.findFiles(projectId).keySet();
            CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter()
                    .importPayload(project.rootPath(), indexedFiles, payload);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
            return snapshot;
        }
    }

    public IndexStatistics inspect(UUID projectId) {
        getProject(projectId);
        return indexRepository.statistics(projectId);
    }

    public SearchOperation search(UUID projectId, String query, int limit, boolean explain) throws IOException {
        String resolvedQuery = requireQuery(query);
        ProjectDescriptor project = requireReadyProject(projectId);
        int resolvedLimit = positiveLimit(limit);
        long startedAt = System.nanoTime();
        List<RankedCandidate> results = searchService.search(project, resolvedQuery, resolvedLimit, explain);
        return new SearchOperation(
                project, resolvedQuery, resolvedLimit, explain, elapsedMillis(startedAt), results);
    }

    public FederatedSearchOperation searchAcrossProjects(
            List<UUID> projectIds,
            String query,
            int limit,
            boolean explain) throws IOException {
        List<UUID> scope = FederatedScopePolicy.normalizeProjectIds(projectIds);
        String resolvedQuery = requireQuery(query);
        int resolvedLimit = positiveLimit(limit);
        List<ProjectDescriptor> projects = scope.stream()
                .map(this::requireReadyProject)
                .toList();
        long startedAt = System.nanoTime();
        List<FederatedSearchHit> results =
                federatedSearchService.search(projects, resolvedQuery, resolvedLimit, explain);
        return new FederatedSearchOperation(
                projects, resolvedQuery, resolvedLimit, explain, elapsedMillis(startedAt), results);
    }

    public ContextOperation context(
            UUID projectId,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        String resolvedQuery = requireQuery(query);
        ProjectDescriptor project = requireReadyProject(projectId);
        long startedAt = System.nanoTime();
        ContextBundle bundle = contextBuilder.build(new ContextRequest(
                projectId,
                resolvedQuery,
                tokenBudget,
                requestedSources == null ? Set.of() : requestedSources,
                constraints == null ? Map.of() : constraints,
                explain));
        return new ContextOperation(project, resolvedQuery, explain, elapsedMillis(startedAt), bundle);
    }

    public FederatedContextOperation contextAcrossProjects(
            List<UUID> projectIds,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        List<UUID> scope = FederatedScopePolicy.normalizeProjectIds(projectIds);
        String resolvedQuery = requireQuery(query);
        List<ProjectDescriptor> projects = scope.stream()
                .map(this::requireReadyProject)
                .toList();
        long startedAt = System.nanoTime();
        FederatedContextBundle bundle = federatedContextService.build(
                projects,
                resolvedQuery,
                tokenBudget,
                requestedSources == null ? Set.of() : requestedSources,
                constraints == null ? Map.of() : constraints,
                explain);
        return new FederatedContextOperation(
                projects, resolvedQuery, explain, elapsedMillis(startedAt), bundle);
    }

    public List<IndexedSymbol> findSymbols(UUID projectId, String query, int limit) {
        String resolvedQuery = requireQuery(query);
        requireReadyProject(projectId);
        return indexRepository.searchSymbols(projectId, resolvedQuery, positiveLimit(limit));
    }

    public List<SymbolRelation> findUsages(UUID projectId, String symbol, int limit) {
        String resolvedSymbol = requireQuery(symbol);
        requireReadyProject(projectId);
        return indexRepository.searchRelations(projectId, resolvedSymbol, positiveLimit(limit));
    }

    public ReadinessSnapshot readiness() {
        List<ProjectDescriptor> projects = projectRepository.findAll();
        EnumMap<IndexStatus, Integer> counts = new EnumMap<>(IndexStatus.class);
        for (IndexStatus status : IndexStatus.values()) {
            counts.put(status, 0);
        }
        projects.forEach(project -> counts.merge(project.indexStatus(), 1, Integer::sum));

        boolean degraded = counts.get(IndexStatus.FAILED) > 0;
        boolean allProjectsReady = !projects.isEmpty() && projects.stream()
                .allMatch(project -> project.indexStatus() == IndexStatus.READY);

        return new ReadinessSnapshot(
                true,
                allProjectsReady,
                degraded,
                projects.size(),
                counts,
                semanticSearchEnabled);
    }

    private ProjectDescriptor requireReadyProject(UUID projectId) {
        ProjectDescriptor project = getProject(projectId);
        if (project.indexStatus() != IndexStatus.READY) {
            throw new IllegalStateException(
                    "Le projet " + project.name() + " n'est pas READY (état " + project.indexStatus() + ")");
        }
        return project;
    }

    private static String requireQuery(String value) {
        return QueryPolicy.normalize(value);
    }

    private static int positiveLimit(int limit) {
        return ResultLimitPolicy.validate(limit);
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
        public SearchOperation {
            results = List.copyOf(results);
        }
    }

    public record FederatedSearchOperation(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchHit> results) {
        public FederatedSearchOperation {
            projects = List.copyOf(projects);
            results = List.copyOf(results);
        }
    }

    public record ContextOperation(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            ContextBundle bundle) {
    }

    public record FederatedContextOperation(
            List<ProjectDescriptor> projects,
            String query,
            boolean explain,
            long durationMs,
            FederatedContextBundle bundle) {
        public FederatedContextOperation {
            projects = List.copyOf(projects);
        }
    }

    public record ReadinessSnapshot(
            boolean operational,
            boolean allProjectsReady,
            boolean degraded,
            int registeredProjects,
            Map<IndexStatus, Integer> projectsByStatus,
            boolean semanticSearchEnabled) {
        public ReadinessSnapshot {
            projectsByStatus = Map.copyOf(projectsByStatus);
        }
    }
}
