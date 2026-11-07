package com.nexus.architecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Règles nommées des frontières du cœur {@code com.nexus}.
 *
 * <p>Chaque violation est rendue sous la forme {@code [règle] classe -> nom importé}. Les
 * références pleinement qualifiées sans import comptent comme des imports.</p>
 */
final class CoreArchitectureRules {

    static final String CORE_WITHOUT_FRAMEWORK = "core-sans-framework";
    static final String LUCENE_CONFINED = "lucene-confine";
    static final String SQLITE_CONFINED = "sqlite-confine";
    static final String PROJECT_LEAF = "project-feuille";
    static final String CYCLE_BASELINE = "cycles-baseline";
    static final String INDEX_WITHOUT_SEARCH = "index-sans-search";
    static final String CONTEXT_SOURCE_WITHOUT_CONTEXT = "context-source-sans-context";
    static final String RANKING_WITHOUT_SEARCH = "ranking-sans-search";
    static final String SEARCH_SEMANTIC_WITHOUT_OLLAMA = "search-semantic-sans-ollama";

    static final List<String> FRAMEWORK_PREFIXES = List.of(
            "io.quarkus.", "jakarta.", "javax.ws.", "org.springframework.", "io.modelcontextprotocol.");
    static final List<String> LUCENE_PREFIXES = List.of("org.apache.lucene.");
    static final List<String> SQLITE_PREFIXES = List.of("org.sqlite.", "java.sql.");

    /** Préfixes surveillés aussi sous forme de nom pleinement qualifié dans le code. */
    static final List<String> WATCHED_QUALIFIED_PREFIXES = concat(FRAMEWORK_PREFIXES, LUCENE_PREFIXES, SQLITE_PREFIXES);

    private static final List<String> LUCENE_PACKAGES = List.of(
            "com.nexus.search.lucene", "com.nexus.search.semantic.lucene");
    private static final List<String> SQLITE_PACKAGES = List.of("com.nexus.persistence.sqlite");
    private static final String PROJECT_PACKAGE = "com.nexus.project";
    private static final String INDEX_PACKAGE = "com.nexus.index";
    private static final String INDEX_SERVICE_PACKAGE = "com.nexus.index.service";
    private static final String SEARCH_PACKAGE = "com.nexus.search";
    private static final String CONTEXT_PACKAGE = "com.nexus.context";
    private static final String CONTEXT_SOURCE_PACKAGE = "com.nexus.context.source";
    private static final List<String> RANKING_PACKAGES = List.of("com.nexus.ranking");
    private static final String SEMANTIC_PACKAGE = "com.nexus.search.semantic";
    private static final String OLLAMA_PACKAGE = "com.nexus.search.semantic.ollama";

    /** Exceptions fermées de {@value #SQLITE_CONFINED} : classe et nom importé exacts. */
    static final Set<String> SQLITE_EXCEPTIONS = Set.of(
            "com.nexus.application.NexusApplication -> java.sql.SQLException",
            "com.nexus.application.NexusApplicationFactory -> java.sql.SQLException",
            "com.nexus.cli.NexusCli -> java.sql.SQLException");

    private CoreArchitectureRules() {
    }

    static List<String> coreWithoutFramework(JavaSourceGraph graph) {
        return check(graph, CORE_WITHOUT_FRAMEWORK, name -> startsWithAny(name, FRAMEWORK_PREFIXES),
                file -> false, edge -> false);
    }

    static List<String> luceneConfined(JavaSourceGraph graph) {
        return check(graph, LUCENE_CONFINED, name -> startsWithAny(name, LUCENE_PREFIXES),
                file -> inAny(file.packageName(), LUCENE_PACKAGES), edge -> false);
    }

    static List<String> sqliteConfined(JavaSourceGraph graph) {
        return check(graph, SQLITE_CONFINED, name -> startsWithAny(name, SQLITE_PREFIXES),
                file -> inAny(file.packageName(), SQLITE_PACKAGES), SQLITE_EXCEPTIONS::contains);
    }

    static List<String> projectLeaf(JavaSourceGraph graph) {
        return check(graph, PROJECT_LEAF, name -> name.startsWith("com.nexus.") && !inAny(name, List.of(PROJECT_PACKAGE)),
                file -> !inAny(file.packageName(), List.of(PROJECT_PACKAGE)), edge -> false);
    }

    /**
     * Compare les arêtes de cycle à la baseline : une arête nouvelle et une entrée devenue fausse
     * sont toutes deux des violations.
     */
    static List<String> cycleBaseline(SortedSet<String> actualCycleEdges, SortedSet<String> baseline) {
        List<String> violations = new ArrayList<>();
        SortedSet<String> added = new TreeSet<>(actualCycleEdges);
        added.removeAll(baseline);
        for (String edge : added) {
            violations.add("[" + CYCLE_BASELINE + "] nouvelle arête de cycle entre packages : " + edge);
        }
        SortedSet<String> stale = new TreeSet<>(baseline);
        stale.removeAll(actualCycleEdges);
        for (String edge : stale) {
            violations.add("[" + CYCLE_BASELINE + "] l'arête " + edge
                    + " n'appartient plus à un cycle : retirer l'entrée de la baseline");
        }
        return violations;
    }

    /**
     * {@value #INDEX_WITHOUT_SEARCH} : le modèle et les ports d'indexation ({@code com.nexus.index}
     * et ses sous-packages, hors l'orchestration {@code index.service}) n'importent rien de
     * {@code com.nexus.search}.
     */
    static List<String> indexWithoutSearch(JavaSourceGraph graph) {
        return check(graph, INDEX_WITHOUT_SEARCH, name -> inAny(name, List.of(SEARCH_PACKAGE)),
                file -> !inAny(file.packageName(), List.of(INDEX_PACKAGE))
                        || inAny(file.packageName(), List.of(INDEX_SERVICE_PACKAGE)),
                edge -> false);
    }

    /**
     * {@value #CONTEXT_SOURCE_WITHOUT_CONTEXT} : les sources de contexte ({@code com.nexus.context.source}
     * et ses sous-packages) n'importent aucun type du package {@code com.nexus.context} ; les sous-packages
     * de modèle, comme {@code context.model}, restent permis.
     */
    static List<String> contextSourceWithoutContext(JavaSourceGraph graph) {
        return check(graph, CONTEXT_SOURCE_WITHOUT_CONTEXT, name -> isTypeOfPackage(name, CONTEXT_PACKAGE),
                file -> !inAny(file.packageName(), List.of(CONTEXT_SOURCE_PACKAGE)), edge -> false);
    }

    /**
     * {@value #RANKING_WITHOUT_SEARCH} : {@code com.nexus.ranking} et {@code ranking.graph} n'importent
     * aucun type du package {@code com.nexus.search} ; {@code search.candidate} reste permis.
     */
    static List<String> rankingWithoutSearch(JavaSourceGraph graph) {
        return check(graph, RANKING_WITHOUT_SEARCH, name -> isTypeOfPackage(name, SEARCH_PACKAGE),
                file -> !inAny(file.packageName(), RANKING_PACKAGES), edge -> false);
    }

    /**
     * {@value #SEARCH_SEMANTIC_WITHOUT_OLLAMA} : le port sémantique ({@code com.nexus.search.semantic})
     * n'importe rien de {@code com.nexus.search.semantic.ollama}, construit par le composition root.
     */
    static List<String> searchSemanticWithoutOllama(JavaSourceGraph graph) {
        return check(graph, SEARCH_SEMANTIC_WITHOUT_OLLAMA, name -> inAny(name, List.of(OLLAMA_PACKAGE)),
                file -> !file.packageName().equals(SEMANTIC_PACKAGE), edge -> false);
    }
    private static List<String> check(JavaSourceGraph graph, String rule, Predicate<String> forbidden,
                                      Predicate<JavaSourceGraph.SourceFile> exempt, Predicate<String> allowedEdge) {
        List<String> violations = new ArrayList<>();
        for (JavaSourceGraph.SourceFile file : graph.files()) {
            if (exempt.test(file)) {
                continue;
            }
            for (String name : file.referencedNames()) {
                String edge = file.className() + " -> " + name;
                if (forbidden.test(name) && !allowedEdge.test(edge)) {
                    violations.add("[" + rule + "] " + edge);
                }
            }
        }
        return violations;
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        return prefixes.stream().anyMatch(name::startsWith);
    }

    /** Vrai si {@code name} est l'un des packages ou se trouve sous l'un d'eux. */
    private static boolean inAny(String name, List<String> packages) {
        return packages.stream().anyMatch(pkg -> name.equals(pkg) || name.startsWith(pkg + "."));
    }

    /** Vrai si {@code name} désigne un type (ou un joker) déclaré directement dans {@code pkg}. */
    private static boolean isTypeOfPackage(String name, String pkg) {
        if (!name.startsWith(pkg + ".")) {
            return false;
        }
        String first = name.substring(pkg.length() + 1).split("\\.")[0];
        return first.equals("*") || (!first.isEmpty() && Character.isUpperCase(first.charAt(0)));
    }
    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> list : lists) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }
}
