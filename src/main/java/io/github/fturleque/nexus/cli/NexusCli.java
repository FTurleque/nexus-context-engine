package io.github.fturleque.nexus.cli;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.context.BudgetedContextSelector;
import io.github.fturleque.nexus.context.ContextBundle;
import io.github.fturleque.nexus.context.ContextBuilder;
import io.github.fturleque.nexus.context.ContextFragmentFactory;
import io.github.fturleque.nexus.context.ContextRequest;
import io.github.fturleque.nexus.context.DefaultContextBuilder;
import io.github.fturleque.nexus.context.FragmentMerger;
import io.github.fturleque.nexus.context.source.instruction.AgentsMdInstructionProvider;
import io.github.fturleque.nexus.context.source.instruction.ClaudeInstructionProvider;
import io.github.fturleque.nexus.context.source.instruction.CopilotInstructionProvider;
import io.github.fturleque.nexus.context.source.instruction.GeminiInstructionProvider;
import io.github.fturleque.nexus.index.IndexRepository;
import io.github.fturleque.nexus.index.IndexStatistics;
import io.github.fturleque.nexus.index.IndexingReport;
import io.github.fturleque.nexus.index.ProjectIndexingService;
import io.github.fturleque.nexus.index.java.JavaParserLanguageAnalyzer;
import io.github.fturleque.nexus.index.markdown.MarkdownLanguageAnalyzer;
import io.github.fturleque.nexus.index.scan.ProjectScanner;
import io.github.fturleque.nexus.persistence.sqlite.SqliteDatabase;
import io.github.fturleque.nexus.persistence.sqlite.SqliteIndexRepository;
import io.github.fturleque.nexus.persistence.sqlite.SqliteProjectRepository;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRegistry;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.ranking.DeterministicContextRanker;
import io.github.fturleque.nexus.ranking.RankedCandidate;
import io.github.fturleque.nexus.ranking.graph.GraphCandidateEnricher;
import io.github.fturleque.nexus.search.SearchIndex;
import io.github.fturleque.nexus.search.SearchService;
import io.github.fturleque.nexus.search.SymbolSearchStrategy;
import io.github.fturleque.nexus.search.lucene.LuceneFileSearchStrategy;
import io.github.fturleque.nexus.search.lucene.LuceneSearchIndex;
import io.github.fturleque.nexus.token.HeuristicTokenEstimator;
import io.github.fturleque.nexus.token.TokenEstimator;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NexusCli {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_RUNTIME_ERROR = 1;
    static final int EXIT_USAGE_ERROR = 2;

    private static final Set<String> COMMANDS = Set.of("project", "index", "search", "context", "inspect");

    private NexusCli() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != EXIT_SUCCESS) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] rawArgs, PrintStream out, PrintStream err) {
        boolean json = Arrays.asList(rawArgs).contains("--json");
        CliRenderer renderer = new CliRenderer(out, err, json);
        try {
            String[] args = withoutJsonFlag(rawArgs);
            run(args, renderer);
            return EXIT_SUCCESS;
        } catch (IllegalArgumentException exception) {
            renderer.renderError(exception.getMessage(), EXIT_USAGE_ERROR);
            return EXIT_USAGE_ERROR;
        } catch (Exception exception) {
            renderer.renderError(safeMessage(exception), EXIT_RUNTIME_ERROR);
            return EXIT_RUNTIME_ERROR;
        }
    }

    private static void run(String[] args, CliRenderer renderer) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "help".equals(args[0])) {
            renderer.renderUsage();
            return;
        }
        if ("--version".equals(args[0]) || "version".equals(args[0])) {
            renderer.renderVersion(version());
            return;
        }
        if (!COMMANDS.contains(args[0])) {
            throw new IllegalArgumentException("Commande inconnue : " + args[0]);
        }

        NexusPaths paths = NexusPaths.fromEnvironment();
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry registry = new ProjectRegistry(projectRepository);
        SearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(
                        new JavaParserLanguageAnalyzer(),
                        new MarkdownLanguageAnalyzer()),
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
                tokenEstimator,
                List.of(
                        new AgentsMdInstructionProvider(),
                        new CopilotInstructionProvider(),
                        new ClaudeInstructionProvider(),
                        new GeminiInstructionProvider()));

        switch (args[0]) {
            case "project" -> handleProject(args, registry, renderer);
            case "index" -> handleIndex(args, projectRepository, indexingService, renderer);
            case "search" -> handleSearch(args, projectRepository, searchService, renderer);
            case "context" -> handleContext(args, projectRepository, contextBuilder, renderer);
            case "inspect" -> handleInspect(args, projectRepository, indexRepository, renderer);
            default -> throw new IllegalStateException("Commande validée mais non routée : " + args[0]);
        }
    }

    private static void handleProject(
            String[] args,
            ProjectRegistry registry,
            CliRenderer renderer) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Commande attendue : project add|list");
        }
        switch (args[1]) {
            case "add" -> {
                if (args.length < 3 || args.length > 4) {
                    throw new IllegalArgumentException("Usage : nexus project add <chemin> [nom] [--json]");
                }
                String name = args.length == 4 ? args[3] : null;
                ProjectDescriptor project = registry.register(Path.of(args[2]), name);
                renderer.renderProject(project);
            }
            case "list" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("Usage : nexus project list [--json]");
                }
                renderer.renderProjects(registry.list());
            }
            default -> throw new IllegalArgumentException("Commande project inconnue : " + args[1]);
        }
    }

    private static void handleIndex(
            String[] args,
            ProjectRepository projectRepository,
            ProjectIndexingService indexingService,
            CliRenderer renderer) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage : nexus index <id-ou-nom> [--rebuild] [--json]");
        }
        ProjectDescriptor project = resolveProject(projectRepository, args[1]);
        boolean rebuild = args.length == 3 && "--rebuild".equals(args[2]);
        if (args.length == 3 && !rebuild) {
            throw new IllegalArgumentException("Option inconnue pour index : " + args[2]);
        }
        IndexingReport report = rebuild
                ? indexingService.rebuild(project.id())
                : indexingService.index(project.id());
        ProjectDescriptor updatedProject = projectRepository.findById(project.id()).orElse(project);
        renderer.renderIndex(updatedProject, report);
    }

    private static void handleSearch(
            String[] args,
            ProjectRepository projectRepository,
            SearchService searchService,
            CliRenderer renderer) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage : nexus search <id-ou-nom> <requête> [--limit N] [--explain] [--json]");
        }

        ProjectDescriptor project = resolveProject(projectRepository, args[1]);
        int limit = 10;
        boolean explain = false;
        List<String> queryParts = new ArrayList<>();

        for (int index = 2; index < args.length; index++) {
            if ("--explain".equals(args[index])) {
                explain = true;
            } else if ("--limit".equals(args[index])) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("--limit attend une valeur entière");
                }
                limit = positiveInteger("--limit", args[++index]);
            } else if (args[index].startsWith("--")) {
                throw new IllegalArgumentException("Option inconnue pour search : " + args[index]);
            } else {
                queryParts.add(args[index]);
            }
        }

        String query = query(queryParts, "La requête de recherche ne peut pas être vide");
        long startedAt = System.nanoTime();
        List<RankedCandidate> results = searchService.search(project, query, limit, explain);
        long durationMs = elapsedMillis(startedAt);
        renderer.renderSearch(project, query, limit, explain, durationMs, results);
    }

    private static void handleContext(
            String[] args,
            ProjectRepository projectRepository,
            ContextBuilder contextBuilder,
            CliRenderer renderer) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage : nexus context <id-ou-nom> <requête> [--budget N] [--explain] [--json]");
        }

        ProjectDescriptor project = resolveProject(projectRepository, args[1]);
        int budget = 2_000;
        boolean explain = false;
        List<String> queryParts = new ArrayList<>();

        for (int index = 2; index < args.length; index++) {
            if ("--explain".equals(args[index])) {
                explain = true;
            } else if ("--budget".equals(args[index])) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("--budget attend une valeur entière");
                }
                budget = positiveInteger("--budget", args[++index]);
            } else if (args[index].startsWith("--")) {
                throw new IllegalArgumentException("Option inconnue pour context : " + args[index]);
            } else {
                queryParts.add(args[index]);
            }
        }

        String query = query(queryParts, "La requête de contexte ne peut pas être vide");
        long startedAt = System.nanoTime();
        ContextBundle bundle = contextBuilder.build(new ContextRequest(
                project.id(),
                query,
                budget,
                Set.of(),
                Map.of(),
                explain));
        long durationMs = elapsedMillis(startedAt);
        renderer.renderContext(project, query, explain, durationMs, bundle);
    }

    private static void handleInspect(
            String[] args,
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            CliRenderer renderer) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage : nexus inspect <id-ou-nom> [--json]");
        }
        ProjectDescriptor project = resolveProject(projectRepository, args[1]);
        IndexStatistics statistics = indexRepository.statistics(project.id());
        renderer.renderInspect(project, statistics);
    }

    private static ProjectDescriptor resolveProject(ProjectRepository repository, String selector) {
        try {
            UUID projectId = UUID.fromString(selector);
            return repository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + selector));
        } catch (IllegalArgumentException notUuidOrMissing) {
            List<ProjectDescriptor> matches = repository.findAll().stream()
                    .filter(project -> project.name().equalsIgnoreCase(selector))
                    .toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException(
                        "Plusieurs projets portent le nom '" + selector + "'. Utilisez leur UUID.");
            }
            throw new IllegalArgumentException("Projet introuvable : " + selector);
        }
    }

    private static int positiveInteger(String option, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(option + " doit être strictement positif");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " attend une valeur entière : " + value, exception);
        }
    }

    private static String query(List<String> parts, String emptyMessage) {
        String query = String.join(" ", parts).trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return query;
    }

    private static String[] withoutJsonFlag(String[] rawArgs) {
        return Arrays.stream(rawArgs)
                .filter(argument -> !"--json".equals(argument))
                .toArray(String[]::new);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static String version() {
        String implementationVersion = NexusCli.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? "0.1.0-SNAPSHOT"
                : implementationVersion;
    }
}
