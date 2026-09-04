package com.nexus.cli;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.context.ContextBudgetPolicy;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.minos.MinosCodeIndexImporter;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.ResultLimitPolicy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NexusCli {

    private static final String CONTEXT_COMMAND = "context";
    private static final String FEDERATED_CONTEXT_COMMAND = "context-federated";

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_RUNTIME_ERROR = 1;
    static final int EXIT_USAGE_ERROR = 2;

    private static final Set<String> COMMANDS = Set.of(
            "project", "index", "minos-import", "search", "search-federated",
            CONTEXT_COMMAND, FEDERATED_CONTEXT_COMMAND, "inspect");

    private NexusCli() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args, System.in, System.out, System.err);
        if (exitCode != EXIT_SUCCESS) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] rawArgs, PrintStream out, PrintStream err) {
        return execute(rawArgs, new ByteArrayInputStream(new byte[0]), out, err);
    }

    static int execute(String[] rawArgs, InputStream input, PrintStream out, PrintStream err) {
        boolean json = Arrays.asList(rawArgs).contains("--json");
        CliRenderer renderer = new CliRenderer(out, err, json);
        try {
            run(withoutJsonFlag(rawArgs), input, renderer);
            return EXIT_SUCCESS;
        } catch (IllegalArgumentException exception) {
            renderer.renderError(exception.getMessage(), EXIT_USAGE_ERROR);
            return EXIT_USAGE_ERROR;
        } catch (Exception exception) {
            renderer.renderError(safeMessage(exception), EXIT_RUNTIME_ERROR);
            return EXIT_RUNTIME_ERROR;
        }
    }

    private static void run(String[] args, InputStream input, CliRenderer renderer) throws IOException, SQLException {
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

        NexusApplication application = NexusApplication.create(NexusPaths.fromEnvironment());
        switch (args[0]) {
            case "project" -> handleProject(args, application, renderer);
            case "index" -> handleIndex(args, application, renderer);
            case "minos-import" -> handleMinosImport(args, input, application, renderer);
            case "search" -> handleSearch(args, application, renderer);
            case "search-federated" -> handleFederatedSearch(args, application, renderer);
            case CONTEXT_COMMAND -> handleContext(args, application, renderer);
            case FEDERATED_CONTEXT_COMMAND -> handleFederatedContext(args, application, renderer);
            case "inspect" -> handleInspect(args, application, renderer);
            default -> throw new IllegalStateException("Commande validée mais non routée : " + args[0]);
        }
    }

    private static void handleProject(String[] args, NexusApplication application, CliRenderer renderer)
            throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Commande attendue : project add|list");
        }
        switch (args[1]) {
            case "add" -> {
                if (args.length < 3 || args.length > 4) {
                    throw new IllegalArgumentException("Usage : nexus project add <chemin> [nom] [--json]");
                }
                String name = args.length == 4 ? args[3] : null;
                renderer.renderProject(application.registerProject(Path.of(args[2]), name));
            }
            case "list" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("Usage : nexus project list [--json]");
                }
                renderer.renderProjects(application.listProjects());
            }
            default -> throw new IllegalArgumentException("Commande project inconnue : " + args[1]);
        }
    }

    private static void handleIndex(String[] args, NexusApplication application, CliRenderer renderer)
            throws IOException {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage : nexus index <id-ou-nom> [--rebuild] [--deep-java] [--json]");
        }
        ProjectDescriptor project = application.resolveProject(args[1]);
        boolean rebuild = false;
        boolean deepJava = false;
        for (int index = 2; index < args.length; index++) {
            switch (args[index]) {
                case "--rebuild" -> rebuild = true;
                case "--deep-java" -> deepJava = true;
                default -> throw new IllegalArgumentException("Option inconnue pour index : " + args[index]);
            }
        }
        NexusApplication.IndexOperation operation = application.index(project.id(), rebuild, deepJava);
        renderer.renderIndex(operation.project(), operation.report());
    }

    private static void handleMinosImport(
            String[] args,
            InputStream input,
            NexusApplication application,
            CliRenderer renderer) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage : nexus minos-import <id-ou-nom> < export-minos.json [--json]");
        }
        ProjectDescriptor project = application.resolveProject(args[1]);
        String payload;
        try {
            payload = MinosCodeIndexImporter.readPayload(input);
        } catch (IOException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("128 MiB transport limit")) {
                throw new IllegalArgumentException("Le payload MINOS dépasse la limite de 128 MiB", exception);
            }
            throw exception;
        }
        if (payload.isBlank()) {
            throw new IllegalArgumentException("Le payload MINOS doit être fourni sur stdin");
        }
        CodeIntelligenceSnapshot snapshot = application.importMinos(project.id(), payload);
        renderer.renderMinosImport(application.getProject(project.id()), snapshot);
    }

    private static void handleSearch(String[] args, NexusApplication application, CliRenderer renderer)
            throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage : nexus search <id-ou-nom> <requête> [--limit N] [--explain] [--json]");
        }
        ProjectDescriptor project = application.resolveProject(args[1]);
        ParsedSearch parsed = parseSearch(args, 2, "search");
        NexusApplication.SearchOperation operation = application.search(
                project.id(), parsed.query(), parsed.limit(), parsed.explain());
        renderer.renderSearch(
                operation.project(), operation.query(), operation.limit(), operation.explain(),
                operation.durationMs(), operation.results());
    }

    private static void handleFederatedSearch(
            String[] args,
            NexusApplication application,
            CliRenderer renderer) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage : nexus search-federated <projet1,projet2,...> <requête> [--limit N] [--explain] [--json]");
        }
        List<UUID> projectIds = resolveProjectScope(application, args[1]);
        ParsedSearch parsed = parseSearch(args, 2, "search-federated");
        NexusApplication.FederatedSearchOperation operation = application.searchAcrossProjects(
                projectIds, parsed.query(), parsed.limit(), parsed.explain());
        renderer.renderFederatedSearch(
                operation.projects(), operation.query(), operation.limit(), operation.explain(),
                operation.durationMs(), operation.results());
    }

    private static void handleContext(String[] args, NexusApplication application, CliRenderer renderer)
            throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage : nexus context <id-ou-nom> <requête> [--budget N] [--explain] [--json]");
        }
        ProjectDescriptor project = application.resolveProject(args[1]);
        ParsedContext parsed = parseContext(args, 2, CONTEXT_COMMAND);
        NexusApplication.ContextOperation operation = application.context(
                project.id(), parsed.query(), parsed.budget(), Set.of(), Map.of(), parsed.explain());
        renderer.renderContext(
                operation.project(), operation.query(), operation.explain(), operation.durationMs(), operation.bundle());
    }

    private static void handleFederatedContext(
            String[] args,
            NexusApplication application,
            CliRenderer renderer) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage : nexus context-federated <projet1,projet2,...> <requête> [--budget N] [--explain] [--json]");
        }
        List<UUID> projectIds = resolveProjectScope(application, args[1]);
        ParsedContext parsed = parseContext(args, 2, FEDERATED_CONTEXT_COMMAND);
        NexusApplication.FederatedContextOperation operation = application.contextAcrossProjects(
                projectIds, parsed.query(), parsed.budget(), Set.of(), Map.of(), parsed.explain());
        renderer.renderFederatedContext(
                operation.projects(), operation.query(), operation.explain(), operation.durationMs(), operation.bundle());
    }

    private static void handleInspect(String[] args, NexusApplication application, CliRenderer renderer)
            throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage : nexus inspect <id-ou-nom> [--json]");
        }
        ProjectDescriptor project = application.resolveProject(args[1]);
        renderer.renderInspect(project, application.inspect(project.id()));
    }

    private static ParsedSearch parseSearch(String[] args, int start, String command) {
        int limit = ResultLimitPolicy.DEFAULT_RESULT_LIMIT;
        boolean explain = false;
        List<String> queryParts = new ArrayList<>();
        for (int index = start; index < args.length; index++) {
            if ("--explain".equals(args[index])) {
                explain = true;
            } else if ("--limit".equals(args[index])) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("--limit attend une valeur entière");
                }
                limit = boundedInteger("--limit", args[++index], ResultLimitPolicy.MAX_RESULT_LIMIT);
            } else if (args[index].startsWith("--")) {
                throw new IllegalArgumentException("Option inconnue pour " + command + " : " + args[index]);
            } else {
                queryParts.add(args[index]);
            }
        }
        return new ParsedSearch(query(queryParts, "La requête de recherche ne peut pas être vide"), limit, explain);
    }

    private static ParsedContext parseContext(String[] args, int start, String command) {
        int budget = ContextBudgetPolicy.DEFAULT_CONTEXT_TOKEN_BUDGET;
        boolean explain = false;
        List<String> queryParts = new ArrayList<>();
        for (int index = start; index < args.length; index++) {
            if ("--explain".equals(args[index])) {
                explain = true;
            } else if ("--budget".equals(args[index])) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("--budget attend une valeur entière");
                }
                budget = boundedInteger(
                        "--budget", args[++index], ContextBudgetPolicy.MAX_CONTEXT_TOKEN_BUDGET);
            } else if (args[index].startsWith("--")) {
                throw new IllegalArgumentException("Option inconnue pour " + command + " : " + args[index]);
            } else {
                queryParts.add(args[index]);
            }
        }
        return new ParsedContext(query(queryParts, "La requête de contexte ne peut pas être vide"), budget, explain);
    }

    private static List<UUID> resolveProjectScope(NexusApplication application, String selectors) {
        List<String> scopeSelectors = Arrays.stream(selectors.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (scopeSelectors.isEmpty()) {
            throw new IllegalArgumentException("La portée fédérée doit contenir au moins un projet");
        }

        FederatedScopePolicy.validateExplicitUuidSelectors(scopeSelectors);
        Set<UUID> ids = new LinkedHashSet<>();
        for (String selector : scopeSelectors) {
            ProjectDescriptor project = application.resolveProject(selector);
            if (ids.add(project.id())) {
                FederatedScopePolicy.validateUniqueCount(ids.size());
            }
        }
        return List.copyOf(ids);
    }

    private static int boundedInteger(String option, String value, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(option + " doit être strictement positif");
            }
            if (parsed > maximum) {
                throw new IllegalArgumentException(option + " doit être inférieur ou égal à " + maximum);
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

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String version() {
        String implementationVersion = NexusCli.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? "0.0.0-dev"
                : implementationVersion;
    }

    private record ParsedSearch(String query, int limit, boolean explain) {
    }

    private record ParsedContext(String query, int budget, boolean explain) {
    }
}
