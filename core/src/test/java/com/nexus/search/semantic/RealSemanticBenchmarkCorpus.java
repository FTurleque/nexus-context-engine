package com.nexus.search.semantic;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Corpus et requêtes du benchmark sémantique réel (GEN-0059).
 *
 * <p>Le corpus {@code full} est le snapshot NEXUS entier. Le corpus {@code pr} en
 * est un sous-ensemble déclaré, copié dans un répertoire dédié avant indexation :
 * il contient tous les chemins jugés pertinents par les requêtes et environ un
 * tiers des entrées embarquées du corpus complet, pour qu'une pull request
 * reçoive un verdict sous 45 minutes.</p>
 */
final class RealSemanticBenchmarkCorpus {

    static final String CORPUS_PROPERTY = "nexus.semantic.realBenchmark.corpus";

    /** Tranches du corpus {@code pr} : répertoire relatif à la racine, motif glob relatif à ce répertoire. */
    static final List<Slice> PR_SLICES = List.of(
            new Slice("docs/developer", "**"),
            new Slice("docs/architecture/adr", "BE-*.md"),
            new Slice("docs/architecture/adr", "DATA-*.md"),
            new Slice("core/src/main/java/com/nexus/context", "**"),
            new Slice("core/src/main/java/com/nexus/search", "**"),
            new Slice("adapters/mcp-java/src/main", "**"));

    private RealSemanticBenchmarkCorpus() {
    }

    enum Kind {
        FULL,
        PR;

        static Kind fromSystemProperties() {
            String configured = System.getProperty(CORPUS_PROPERTY, "full").trim().toLowerCase(Locale.ROOT);
            return switch (configured) {
                case "full" -> FULL;
                case "pr" -> PR;
                default -> throw new IllegalArgumentException(
                        CORPUS_PROPERTY + " doit valoir full ou pr : " + configured);
            };
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    record Slice(String directory, String glob) {
    }

    record QueryCase(String query, Set<String> relevantPaths) {
        QueryCase {
            relevantPaths = Set.copyOf(relevantPaths);
        }
    }

    static List<QueryCase> queries() {
        // Chaque requête décrit un besoin d'information. Les jugements de pertinence
        // acceptent la documentation d'architecture et l'implémentation canonique qui
        // satisfont indépendamment ce besoin ; le moteur NEXUS recherche les deux.
        return List.of(
                new QueryCase(
                        "generated lookup state should be disposable and recoverable from the authoritative metadata store",
                        Set.of(
                                "docs/architecture/adr/DATA-0022-traiter-lucene-comme-un-index-derive-reconstructible-de-sqlite.md",
                                "docs/developer/index-provenance.md")),
                new QueryCase(
                        "screen lightweight capability summaries before loading the full operating procedure",
                        Set.of(
                                "docs/developer/agent-skills.md",
                                "docs/architecture/adr/BE-0034-adopter-la-divulgation-progressive-pour-les-agent-skills.md")),
                new QueryCase(
                        "version-control activity should influence relevance while remaining completely offline and read-only",
                        Set.of(
                                "docs/developer/git-context.md",
                                "core/src/main/java/com/nexus/context/source/git/LocalGitContextSourceProvider.java")),
                new QueryCase(
                        "let agent clients invoke project search through a standard stdio tool bridge without coupling the engine core",
                        Set.of(
                                "docs/developer/mcp.md",
                                "adapters/mcp-java/src/main/java/com/nexus/mcp/NexusMcpTools.java")),
                new QueryCase(
                        "combine results from several codebases while keeping every hit tied to its repository of origin",
                        Set.of(
                                "docs/developer/large-scale-search.md",
                                "docs/architecture/adr/BE-0043-federer-la-recherche-locale-par-projet-avant-un-moteur-externe.md",
                                "core/src/main/java/com/nexus/search/FederatedSearchService.java")),
                new QueryCase(
                        "retain only the most useful fragments that fit inside a bounded language-model input allowance",
                        Set.of(
                                "docs/architecture/adr/BE-0013-construire-un-contextbundle-sous-budget-de-tokens.md",
                                "docs/developer/context-building.md",
                                "core/src/main/java/com/nexus/context/BudgetedContextSelector.java")));
    }

    /** Chemins relatifs, au format {@code a/b/c}, des fichiers du corpus {@code pr} sous {@code root}. */
    static Set<String> prCorpusFiles(Path root) throws IOException {
        Set<String> files = new LinkedHashSet<>();
        for (Slice slice : PR_SLICES) {
            Path directory = root.resolve(slice.directory());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Tranche du corpus pr absente : " + slice.directory());
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + slice.glob());
            List<String> sliceFiles = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> matcher.matches(directory.relativize(path)))
                        .map(path -> normalize(root.relativize(path)))
                        .sorted()
                        .forEach(sliceFiles::add);
            }
            if (sliceFiles.isEmpty()) {
                throw new IOException("Tranche du corpus pr vide : " + slice.directory() + "/" + slice.glob());
            }
            files.addAll(sliceFiles);
        }
        return Collections.unmodifiableSet(files);
    }

    /** Chemins pertinents des requêtes absents du corpus {@code pr}. */
    static List<String> missingRelevantPaths(Set<String> corpusFiles) {
        return queries().stream()
                .flatMap(queryCase -> queryCase.relevantPaths().stream())
                .filter(path -> !corpusFiles.contains(path))
                .distinct()
                .sorted()
                .toList();
    }

    /** Copie le corpus {@code pr} de {@code root} dans {@code target} et retourne {@code target}. */
    static Path materializePrCorpus(Path root, Path target) throws IOException {
        Set<String> files = prCorpusFiles(root);
        List<String> missing = missingRelevantPaths(files);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Le corpus pr doit contenir chaque chemin pertinent : " + missing);
        }
        for (String relative : files) {
            Path destination = target.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(root.resolve(relative), destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
        return target;
    }

    static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
