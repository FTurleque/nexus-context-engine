package com.nexus.application;

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
import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.IndexingReport;
import com.nexus.index.ProjectIndexingService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Façade applicative indépendante des adaptateurs clients.
 *
 * <p>Elle centralise la composition du moteur NEXUS afin que la CLI, REST, MCP
 * et les futurs adaptateurs puissent partager exactement les mêmes services
 * métier sans dépendre d'un framework applicatif ou d'un protocole client.</p>
 */
public final class NexusApplication {

    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectRegistry projectRegistry;
    private final ProjectIndexingService indexingService;
    private final SearchService searchService;
    private final FederatedSearchService federatedSearchService;
    private final ContextBuilder contextBuilder;

    private NexusApplication(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectRegistry projectRegistry,
            ProjectIndexingService indexingService,
            SearchService searchService,
            FederatedSearchService federatedSearchService,
            ContextBuilder contextBuilder) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.indexingService = Objects.requireNonNull(indexingService, "indexingService");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.federatedSearchService = Objects.requireNonNull(federatedSearchService, "federatedSearchService");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    }

    public static NexusApplication create(NexusPaths paths) throws SQLException, IOException {
        return create(paths, SemanticSearchConfiguration.disabled());
    }

    /**
     * Compose NEXUS avec une capacité sémantique uniquement lorsque l'appelant
     * fournit explicitement une configuration activée.
     */
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
            searchStrategies.add(new SemanticSearchStrategy(embeddingProvider, semanticSearchIndex));
        }

        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(
                        new JavaParserLanguageAnalyzer(),
                        new MarkdownLanguageAnalyzer()),
                searchIndex,
                codeIndexImporters,
                codeIntelligenceProviders,
                semanticIndexingService);

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
                List.of(new LocalAgentSkillsProvider()),
                new LocalGitContextSourceProvider());

        return new NexusApplication(
                projectRepository,
                indexRepository,
                projectRegistry,
                indexingService,
                searchService,
                federatedSearchService,
                contextBuilder);
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
        try {
            UUID projectId = UUID.fromString(normalized);
            return getProject(projectId);
        } catch (IllegalArgumentException notUuidOrMissing) {
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

    /**
     * Replaces the explicit MINOS external snapshot for a registered project.
     * The payload is supplied by the caller; NEXUS never launches MINOS itself.
     */
    public CodeIntelligenceSnapshot importMinos(UUID projectId, String payload) throws IOException {
        ProjectDescriptor project = getProject(projectId);
        CodeIntelligenceSnapshot snapshot = new MinosCodeIndexImporter().importPayload(project.rootPath(), payload);
        indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
        return snapshot;
    }

    public IndexStatistics inspect(UUID projectId) {
        getProject(projectId);
        return indexRepository.statistics(projectId);
    }

    public SearchOperation search(UUID projectId, String query, int limit, boolean explain) throws IOException {
        ProjectDescriptor project = getProject(projectId);
        long startedAt = System.nanoTime();
        List<RankedCandidate> results = searchService.search(project, query, limit, explain);
        return new SearchOperation(project, query, limit, explain, elapsedMillis(startedAt), results);
    }

    public FederatedSearchOperation searchAcrossProjects(
            List<UUID> projectIds,
            String query,
            int limit,
            boolean explain) throws IOException {
        Objects.requireNonNull(projectIds, "projectIds");
        List<ProjectDescriptor> projects = projectIds.stream()
                .map(this::getProject)
                .toList();
        long startedAt = System.nanoTime();
        List<FederatedSearchHit> results = federatedSearchService.search(projects, query, limit, explain);
        return new FederatedSearchOperation(projects, query, limit, explain, elapsedMillis(startedAt), results);
    }

    public ContextOperation context(
            UUID projectId,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        ProjectDescriptor project = getProject(projectId);
        long startedAt = System.nanoTime();
        ContextBundle bundle = contextBuilder.build(new ContextRequest(
                projectId,
                query,
                tokenBudget,
                requestedSources == null ? Set.of() : requestedSources,
                constraints == null ? Map.of() : constraints,
                explain));
        return new ContextOperation(project, query, explain, elapsedMillis(startedAt), bundle);
    }

    public List<IndexedSymbol> findSymbols(UUID projectId, String query, int limit) {
        getProject(projectId);
        String normalized = requireQuery(query).toLowerCase(Locale.ROOT);
        int boundedLimit = positiveLimit(limit);
        return indexRepository.findSymbols(projectId).stream()
                .filter(indexed -> {
                    String name = indexed.symbol().name().toLowerCase(Locale.ROOT);
                    String qualifiedName = indexed.symbol().qualifiedName().toLowerCase(Locale.ROOT);
                    return name.contains(normalized) || qualifiedName.contains(normalized);
                })
                .sorted((left, right) -> {
                    boolean leftExact = left.symbol().name().equalsIgnoreCase(query);
                    boolean rightExact = right.symbol().name().equalsIgnoreCase(query);
                    if (leftExact != rightExact) {
                        return leftExact ? -1 : 1;
                    }
                    int byName = left.symbol().qualifiedName().compareTo(right.symbol().qualifiedName());
                    return byName != 0 ? byName : left.relativePath().compareTo(right.relativePath());
                })
                .limit(boundedLimit)
                .toList();
    }

    public List<SymbolRelation> findUsages(UUID projectId, String symbol, int limit) {
        getProject(projectId);
        String normalized = requireQuery(symbol).toLowerCase(Locale.ROOT);
        int boundedLimit = positiveLimit(limit);
        return indexRepository.findRelations(projectId).stream()
                .filter(relation -> relation.source().toLowerCase(Locale.ROOT).contains(normalized)
                        || relation.target().toLowerCase(Locale.ROOT).contains(normalized))
                .sorted((left, right) -> {
                    int byKind = left.kind().compareTo(right.kind());
                    if (byKind != 0) {
                        return byKind;
                    }
                    int bySource = left.source().compareTo(right.source());
                    return bySource != 0 ? bySource : left.target().compareTo(right.target());
                })
                .limit(boundedLimit)
                .toList();
    }

    private static String requireQuery(String value) {
        Objects.requireNonNull(value, "query");
        String normalized = value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("La requête ne peut pas être vide");
        }
        return normalized;
    }

    private static int positiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit doit être strictement positif");
        }
        return Math.min(limit, 500);
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
}
