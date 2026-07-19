package io.github.fturleque.nexus.cli;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.index.IndexRepository;
import io.github.fturleque.nexus.index.IndexStatistics;
import io.github.fturleque.nexus.index.IndexingReport;
import io.github.fturleque.nexus.index.ProjectIndexingService;
import io.github.fturleque.nexus.index.java.JavaParserLanguageAnalyzer;
import io.github.fturleque.nexus.index.scan.ProjectScanner;
import io.github.fturleque.nexus.persistence.sqlite.SqliteDatabase;
import io.github.fturleque.nexus.persistence.sqlite.SqliteIndexRepository;
import io.github.fturleque.nexus.persistence.sqlite.SqliteProjectRepository;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRegistry;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.search.lucene.LuceneSearchIndex;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public final class NexusCli {

    private NexusCli() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            System.err.println("Erreur NEXUS : " + exception.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        NexusPaths paths = NexusPaths.fromEnvironment();
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry registry = new ProjectRegistry(projectRepository);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths));

        switch (args[0]) {
            case "project" -> handleProject(args, registry);
            case "index" -> handleIndex(args, projectRepository, indexingService);
            case "inspect" -> handleInspect(args, projectRepository, indexRepository);
            default -> throw new IllegalArgumentException("Commande inconnue : " + args[0]);
        }
    }

    private static void handleProject(String[] args, ProjectRegistry registry) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Commande attendue : project add|list");
        }
        switch (args[1]) {
            case "add" -> {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage : nexus project add <chemin> [nom]");
                }
                String name = args.length >= 4 ? args[3] : null;
                ProjectDescriptor project = registry.register(Path.of(args[2]), name);
                printProject(project);
            }
            case "list" -> registry.list().forEach(NexusCli::printProject);
            default -> throw new IllegalArgumentException("Commande project inconnue : " + args[1]);
        }
    }

    private static void handleIndex(
            String[] args,
            ProjectRepository projectRepository,
            ProjectIndexingService indexingService) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage : nexus index <id-ou-nom> [--rebuild]");
        }
        ProjectDescriptor project = resolveProject(projectRepository, args[1]);
        boolean rebuild = args.length >= 3 && "--rebuild".equals(args[2]);
        IndexingReport report = rebuild
                ? indexingService.rebuild(project.id())
                : indexingService.index(project.id());

        System.out.printf(
                "Projet %s : %d scannés, %d modifiés, %d supprimés, %d fichiers / %d symboles / %d relations, %d ms%s%n",
                project.name(),
                report.scannedFiles(),
                report.changedFiles(),
                report.removedFiles(),
                report.statistics().files(),
                report.statistics().symbols(),
                report.statistics().relations(),
                report.duration().toMillis(),
                report.fullSearchRebuild() ? " (reconstruction complète)" : "");
    }

    private static void handleInspect(
            String[] args,
            ProjectRepository projectRepository,
            IndexRepository indexRepository) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage : nexus inspect <id-ou-nom>");
        }
        ProjectDescriptor project = resolveProject(projectRepository, args[1]);
        IndexStatistics statistics = indexRepository.statistics(project.id());
        printProject(project);
        System.out.printf(
                "Index : %d fichiers, %d symboles, %d relations%n",
                statistics.files(),
                statistics.symbols(),
                statistics.relations());
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

    private static void printProject(ProjectDescriptor project) {
        System.out.printf(
                "%s\t%s\t%s\t%s%n",
                project.id(),
                project.name(),
                project.indexStatus(),
                project.rootPath());
    }

    private static void printUsage() {
        System.out.println("""
                NEXUS Context Engine

                Commandes disponibles :
                  nexus project add <chemin> [nom]
                  nexus project list
                  nexus index <id-ou-nom> [--rebuild]
                  nexus inspect <id-ou-nom>
                """);
    }
}
