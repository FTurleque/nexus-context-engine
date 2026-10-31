package com.nexus.exposure;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Structures publiques produites par {@link PublicOutputProjection} et sérialisées telles quelles
 * par la CLI (JSON), REST et MCP.
 *
 * <p>L'ordre des composantes de chaque record est l'ordre des clés JSON. Les maps exposées sont
 * triées par clé. Un adaptateur ne recompose jamais ces structures : il les sérialise, et la seule
 * divergence admise est déclarée par {@link PublicOutputSchema}.</p>
 */
public final class PublicOutput {

    private PublicOutput() {
    }

    /** Projet enregistré ; sa racine absolue n'est exposée sur aucune surface publique. */
    public record Project(
            String id,
            String name,
            String sourceType,
            List<String> languages,
            List<String> technologies,
            String lastIndexedAt,
            String indexStatus) {
        public Project {
            languages = List.copyOf(languages);
            technologies = List.copyOf(technologies);
        }
    }

    /** Symbole de code ; {@code qualifiedName} et {@code signature} sont redigés. */
    public record Symbol(
            String kind,
            String name,
            String qualifiedName,
            String signature,
            int startLine,
            int endLine,
            String sourceProvider) {
    }

    /** Résultat classé d'une recherche ; {@code symbol} vaut {@code null} pour un fichier. */
    public record RankedResult(
            int rank,
            String id,
            String type,
            String path,
            String excerpt,
            double score,
            SortedMap<String, Double> scoreComponents,
            List<String> reasons,
            Symbol symbol) {
        public RankedResult {
            scoreComponents = sorted(scoreComponents);
            reasons = List.copyOf(reasons);
        }
    }

    public record Search(
            Project project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedResult> results) {
        public Search {
            results = List.copyOf(results);
        }
    }

    public record FederatedSearchResult(Project project, RankedResult result) {
    }

    public record FederatedSearch(
            List<Project> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchResult> results) {
        public FederatedSearch {
            projects = List.copyOf(projects);
            results = List.copyOf(results);
        }
    }

    /** Élément de contexte ; {@code path} est relatif au projet, {@code content} est redigé. */
    public record ContextItem(
            String type,
            String path,
            String symbol,
            int startLine,
            int endLine,
            String content,
            double score,
            SortedMap<String, Double> scoreComponents,
            List<String> reasons,
            int estimatedTokens,
            boolean truncated) {
        public ContextItem {
            scoreComponents = sorted(scoreComponents);
            reasons = List.copyOf(reasons);
        }
    }

    public record Context(
            Project project,
            String query,
            boolean explain,
            long durationMs,
            int tokenBudget,
            int estimatedTokens,
            List<ContextItem> items,
            List<String> excluded,
            SortedMap<String, Object> metadata) {
        public Context {
            items = List.copyOf(items);
            excluded = List.copyOf(excluded);
            metadata = sorted(metadata);
        }
    }

    public record FederatedContextItem(Project project, ContextItem item) {
    }

    public record FederatedContext(
            List<Project> projects,
            String query,
            boolean explain,
            long durationMs,
            int tokenBudget,
            int estimatedTokens,
            List<FederatedContextItem> items,
            List<String> excluded,
            SortedMap<String, Object> metadata) {
        public FederatedContext {
            projects = List.copyOf(projects);
            items = List.copyOf(items);
            excluded = List.copyOf(excluded);
            metadata = sorted(metadata);
        }
    }

    /** Symbole indexé, retourné par {@code find_symbol}. */
    public record IndexedSymbol(String path, Symbol symbol) {
    }

    public record SymbolSearch(Project project, String query, List<IndexedSymbol> symbols) {
        public SymbolSearch {
            symbols = List.copyOf(symbols);
        }
    }

    /** Relation structurelle ; {@code source} et {@code target} sont redigés. */
    public record Relation(
            String kind,
            String source,
            String target,
            double confidence,
            String sourceProvider) {
    }

    public record Usages(Project project, String symbol, List<Relation> relations) {
        public Usages {
            relations = List.copyOf(relations);
        }
    }

    public record IndexStatistics(long files, long symbols, long relations) {
    }

    public record IndexReport(
            int scannedFiles,
            int changedFiles,
            int removedFiles,
            int skippedFiles,
            List<String> diagnostics,
            boolean fullSearchRebuild,
            long durationMs,
            IndexStatistics statistics) {
        public IndexReport {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record Index(Project project, IndexReport report) {
    }

    private static <V> SortedMap<String, V> sorted(java.util.Map<String, V> values) {
        return Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }
}
