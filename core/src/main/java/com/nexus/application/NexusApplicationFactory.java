package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.context.BudgetedContextSelector;
import com.nexus.context.ContextBuilder;
import com.nexus.context.ContextFragmentFactory;
import com.nexus.context.DefaultContextBuilder;
import com.nexus.context.FederatedContextService;
import com.nexus.context.FragmentMerger;
import com.nexus.context.source.git.GitContextSourceProvider;
import com.nexus.context.source.git.GitRecencyCandidateEnricher;
import com.nexus.context.source.git.LocalGitContextSourceProvider;
import com.nexus.context.source.git.PersistentGitContextSourceProvider;
import com.nexus.context.source.instruction.AgentsMdInstructionProvider;
import com.nexus.context.source.instruction.ClaudeInstructionProvider;
import com.nexus.context.source.instruction.CopilotInstructionProvider;
import com.nexus.context.source.instruction.GeminiInstructionProvider;
import com.nexus.context.source.skill.AiSkillsRegistryProvider;
import com.nexus.context.source.skill.LocalAgentSkillsProvider;
import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.IndexRepository;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.index.scip.ScipCodeIndexImporter;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.ContextRanker;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.SemanticHybridContextRanker;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.FederatedSearchService;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchService;
import com.nexus.search.SearchStrategy;
import com.nexus.search.SymbolSearchStrategy;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.search.lucene.PersistentLuceneSearchIndex;
import com.nexus.search.semantic.EmbeddingProvider;
import com.nexus.search.semantic.SemanticIndexingService;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import com.nexus.search.semantic.SemanticSearchIndex;
import com.nexus.search.semantic.SemanticSearchStrategy;
import com.nexus.search.semantic.lucene.LuceneSemanticSearchIndex;
import com.nexus.search.semantic.lucene.PersistentLuceneSemanticSearchIndex;
import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Composition explicite des ports et adaptateurs locaux de la façade applicative. */
final class NexusApplicationFactory {
    private NexusApplicationFactory() { }
    static NexusApplication create(
            NexusPaths paths,
            SemanticSearchConfiguration semanticSearchConfiguration,
            boolean persistentReaders) throws SQLException, IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(semanticSearchConfiguration, "semanticSearchConfiguration");

        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry projectRegistry = new ProjectRegistry(projectRepository);
        SearchIndex searchIndex = persistentReaders
                ? new PersistentLuceneSearchIndex(paths)
                : new LuceneSearchIndex(paths);
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
        SemanticSearchIndex semanticSearchIndex = null;
        if (semanticSearchConfiguration.enabled()) {
            EmbeddingProvider embeddingProvider = semanticSearchConfiguration.embeddingProvider()
                    .orElseThrow(() -> new IllegalStateException("Configuration sémantique activée sans provider"));
            semanticSearchIndex = persistentReaders
                    ? new PersistentLuceneSemanticSearchIndex(paths, embeddingProvider.dimensions())
                    : new LuceneSemanticSearchIndex(paths, embeddingProvider.dimensions());
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
        GitContextSourceProvider gitContextProvider = persistentReaders
                ? new PersistentGitContextSourceProvider()
                : new LocalGitContextSourceProvider();
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
                gitContextProvider);
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
                searchIndex,
                semanticSearchIndex,
                semanticSearchConfiguration.enabled());
    }

}
