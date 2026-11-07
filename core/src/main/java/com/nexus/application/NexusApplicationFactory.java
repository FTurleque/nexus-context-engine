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
import com.nexus.index.SemanticIndexing;
import com.nexus.index.service.ProjectIndexingService;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.index.minos.MinosCodeIndexImporter;
import com.nexus.index.scip.ScipCodeIndexImporter;
import com.nexus.persistence.PersistenceException;
import com.nexus.index.scip.ScipIndexLimits;
import com.nexus.index.scan.ProjectScanLimits;
import com.nexus.context.ContextMaterializationLimits;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.security.ProjectFileLimits;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.project.FederationDeadlinePolicy;
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
import com.nexus.search.semantic.ollama.OllamaEmbeddingProvider;
import com.nexus.search.semantic.ollama.OllamaEndpointResolver;
import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Composition explicite des ports et adaptateurs locaux de la façade applicative.
 *
 * <p>Seule classe du cœur qui lit l'environnement du processus ({@link #processEnvironment()}) :
 * la configuration {@code NEXUS_*} y est résolue en {@link NexusConfiguration}, puis injectée
 * dans les composants (ARCH-0066).</p>
 */
final class NexusApplicationFactory {
    private NexusApplicationFactory() { }
    static NexusApplication create(
            NexusPaths paths,
            SemanticSearchConfiguration semanticSearchConfiguration,
            boolean persistentReaders) throws IOException {
        Objects.requireNonNull(paths, "paths");
        return create(
                paths,
                semanticSearchConfiguration,
                configuration(paths, processEnvironment()),
                persistentReaders);
    }

    /**
     * Résout la configuration {@code NEXUS_*} depuis {@code environment} (l'environnement du
     * processus en production, une table en test). Une valeur invalide échoue ici, avec le
     * message de la classe de limites concernée.
     */
    static NexusConfiguration configuration(NexusPaths paths, Map<String, String> environment) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(environment, "environment");
        ScipIndexLimits scipIndexLimits = ScipIndexLimits.from(environment);
        return new NexusConfiguration(
                ProjectFileLimits.maxFileSize(environment),
                ProjectScanLimits.from(environment),
                ContextMaterializationLimits.from(environment),
                ContextDiscoveryLimits.from(environment),
                scipIndexLimits.maxIndexBytes(),
                scipIndexLimits.maxMessageBytes(),
                ProjectIndexLockManager.maxConcurrentIndexing(environment),
                ProjectIndexingService.providerTimeout(environment),
                JdtLanguageServerCodeIntelligenceProvider.configuration(paths, environment),
                NexusConfiguration.securityPosture(environment));
    }

    static NexusApplication create(
            NexusPaths paths,
            SemanticSearchConfiguration semanticSearchConfiguration,
            NexusConfiguration configuration,
            boolean persistentReaders) throws IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(semanticSearchConfiguration, "semanticSearchConfiguration");
        Objects.requireNonNull(configuration, "configuration");
        configuration.installProcessPolicies();

        ProjectScanner projectScanner = new ProjectScanner(
                configuration.maxFileSizeBytes(), configuration.projectScanLimits());
        SqliteDatabase database = openDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry projectRegistry = new ProjectRegistry(projectRepository);
        SearchIndex searchIndex = persistentReaders
                ? new PersistentLuceneSearchIndex(paths)
                : new LuceneSearchIndex(paths);
        ProjectIndexLockManager projectIndexLockManager =
                ProjectIndexLockManager.fileBacked(paths, configuration.maxConcurrentIndexing());

        List<CodeIntelligenceProvider> codeIntelligenceProviders = configuration.jdtConfiguration()
                .<List<CodeIntelligenceProvider>>map(jdt -> List.of(
                        new JdtLanguageServerCodeIntelligenceProvider(jdt, projectScanner)))
                .orElseGet(List::of);
        List<CodeIndexImporter> codeIndexImporters =
                List.of(new ScipCodeIndexImporter(new ScipIndexLimits(
                        configuration.maxScipIndexBytes(), configuration.maxScipMessageBytes())));

        List<SearchStrategy> searchStrategies = new ArrayList<>();
        searchStrategies.add(new LuceneFileSearchStrategy(searchIndex));
        searchStrategies.add(new SymbolSearchStrategy(indexRepository));

        SemanticIndexing semanticIndexingService = SemanticIndexing.disabled();
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
                projectScanner,
                List.of(new JavaParserLanguageAnalyzer(), new MarkdownLanguageAnalyzer()),
                searchIndex,
                codeIndexImporters,
                codeIntelligenceProviders,
                semanticIndexingService,
                new MinosCodeIndexImporter(projectScanner),
                configuration.codeIntelligenceTimeout(),
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
                new ContextFragmentFactory(tokenEstimator, configuration.contextMaterializationLimits()),
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
                gitContextProvider,
                configuration.contextMaterializationLimits(),
                configuration.contextDiscoveryLimits());
        FederatedContextService federatedContextService = new FederatedContextService(
                contextBuilder,
                FederationDeadlinePolicy.fromEnvironment(),
                configuration.contextMaterializationLimits(),
                configuration.contextDiscoveryLimits());

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

    /**
     * Ouvre le stockage canonique. L'échec JDBC traverse la façade sous
     * {@link PersistenceException}, cause conservée : les adaptateurs n'importent pas
     * {@code java.sql}.
     */
    private static SqliteDatabase openDatabase(NexusPaths paths) throws IOException {
        try {
            return new SqliteDatabase(paths);
        } catch (SQLException failure) {
            throw new PersistenceException("Impossible d'ouvrir le stockage SQLite NEXUS", failure);
        }
    }

    /**
     * Résout l'opt-in opérationnel commun à tous les adaptateurs.
     *
     * <p>Sans {@code NEXUS_SEMANTIC_PROVIDER}, la capacité reste strictement
     * désactivée. La seule valeur supportée actuellement est {@code ollama}.</p>
     */
    static SemanticSearchConfiguration semanticSearchConfigurationFromEnvironment() {
        return semanticSearchConfiguration(processEnvironment());
    }

    /** Résout l'opt-in sémantique depuis l'environnement fourni (table en test). */
    static SemanticSearchConfiguration semanticSearchConfiguration(Map<String, String> variables) {
        Objects.requireNonNull(variables, "variables");
        String provider = environment(variables, SemanticSearchConfiguration.PROVIDER_ENV, "");
        if (provider.isBlank() || "disabled".equalsIgnoreCase(provider) || "off".equalsIgnoreCase(provider)) {
            return SemanticSearchConfiguration.disabled();
        }
        if (!"ollama".equals(provider.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    SemanticSearchConfiguration.PROVIDER_ENV + " supporte uniquement 'ollama' ou 'disabled' actuellement");
        }

        URI configuredBaseUri = URI.create(environment(variables, 
                SemanticSearchConfiguration.OLLAMA_BASE_URL_ENV,
                OllamaEmbeddingProvider.DEFAULT_BASE_URI.toString()));
        boolean dockerRuntime = "docker".equalsIgnoreCase(environment(variables, SemanticSearchConfiguration.RUNTIME_ENV, ""));
        boolean allowInsecureRemote = booleanValue(
                SemanticSearchConfiguration.ALLOW_INSECURE_REMOTE_OLLAMA_ENV,
                environment(variables, SemanticSearchConfiguration.ALLOW_INSECURE_REMOTE_OLLAMA_ENV, "false"));
        URI baseUri = OllamaEndpointResolver.resolveForRuntime(
                configuredBaseUri,
                dockerRuntime,
                allowInsecureRemote);
        boolean runtimeLoopbackRewrite = dockerRuntime && !baseUri.equals(configuredBaseUri);
        String model = environment(variables, SemanticSearchConfiguration.OLLAMA_MODEL_ENV, OllamaEmbeddingProvider.DEFAULT_MODEL);
        int dimensions = SemanticSearchConfiguration.boundedPositiveInt(
                SemanticSearchConfiguration.OLLAMA_DIMENSIONS_ENV,
                environment(variables, SemanticSearchConfiguration.OLLAMA_DIMENSIONS_ENV, Integer.toString(OllamaEmbeddingProvider.DEFAULT_DIMENSIONS)),
                SemanticSearchConfiguration.MAX_OLLAMA_DIMENSIONS);
        long timeoutSeconds = SemanticSearchConfiguration.boundedPositiveLong(
                SemanticSearchConfiguration.OLLAMA_TIMEOUT_SECONDS_ENV,
                environment(variables, SemanticSearchConfiguration.OLLAMA_TIMEOUT_SECONDS_ENV,
                        Long.toString(OllamaEmbeddingProvider.DEFAULT_TIMEOUT.toSeconds())),
                SemanticSearchConfiguration.MAX_OLLAMA_TIMEOUT_SECONDS);
        double weight = positiveDouble(
                SemanticSearchConfiguration.RRF_WEIGHT_ENV,
                environment(variables, SemanticSearchConfiguration.RRF_WEIGHT_ENV, Double.toString(SemanticSearchConfiguration.DEFAULT_SEMANTIC_RRF_WEIGHT)));

        OllamaEmbeddingProvider ollama = new OllamaEmbeddingProvider(
                baseUri,
                model,
                dimensions,
                Duration.ofSeconds(timeoutSeconds),
                allowInsecureRemote || runtimeLoopbackRewrite);
        return SemanticSearchConfiguration.enabled(ollama, weight);
    }

    /**
     * Environnement du processus : seule lecture de {@code System.getenv} du cœur, contrôlée
     * par {@code CoreArchitectureTest} (ARCH-0066).
     */
    static Map<String, String> processEnvironment() {
        return System.getenv();
    }

    private static String environment(Map<String, String> variables, String name, String defaultValue) {
        String value = variables.get(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static boolean booleanValue(String name, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " doit valoir true ou false");
    }

    private static double positiveDouble(String name, String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed <= 0.0d || parsed > SemanticSearchConfiguration.MAX_SEMANTIC_RRF_WEIGHT) {
                throw new IllegalArgumentException(
                        name + " doit être > 0 et <= " + SemanticSearchConfiguration.MAX_SEMANTIC_RRF_WEIGHT);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " doit être un nombre", exception);
        }
    }
}
