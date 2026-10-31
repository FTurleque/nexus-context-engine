package com.nexus.exposure;

import com.nexus.context.ContextBundle;
import com.nexus.context.FederatedContextBundle;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexingReport;
import com.nexus.index.SymbolRelation;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.FederatedSearchHit;
import com.nexus.security.PublicDiagnosticPolicy;
import com.nexus.security.PublicProjectPathPolicy;
import com.nexus.security.SensitiveContentRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Projection publique unique des résultats NEXUS, partagée par la CLI, REST et MCP (ADR-0061).
 *
 * <p>Elle applique en un seul endroit la politique de sortie : chemins relatifs au projet via
 * {@link PublicProjectPathPolicy}, diagnostics filtrés par {@link PublicDiagnosticPolicy},
 * redaction des extraits, du contenu et des métadonnées de symbole d'origine externe, et tri par
 * clé des maps exposées. Un champ court que la redaction refuse sort en {@code [REDACTED]} : une
 * métadonnée de symbole trop longue ne fait pas échouer la réponse.</p>
 */
public final class PublicOutputProjection {

    private PublicOutputProjection() {
    }

    public static PublicOutput.Project project(ProjectDescriptor project) {
        return new PublicOutput.Project(
                project.id().toString(),
                project.name(),
                project.sourceType().name(),
                project.languages().stream().sorted().toList(),
                project.technologies().stream().sorted().toList(),
                project.lastIndexedAt() == null ? null : project.lastIndexedAt().toString(),
                project.indexStatus().name());
    }

    public static List<PublicOutput.Project> projects(List<ProjectDescriptor> projects) {
        return projects.stream().map(PublicOutputProjection::project).toList();
    }

    /**
     * Liste des projets enregistrés, dans le même ordre sur les trois canaux : nom sans tenir compte
     * de la casse, puis identifiant. Une portée fédérée garde l'ordre demandé ({@link #projects}).
     */
    public static List<PublicOutput.Project> projectList(List<ProjectDescriptor> projects) {
        return projects.stream()
                .sorted(java.util.Comparator.comparing(ProjectDescriptor::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(project -> project.id().toString()))
                .map(PublicOutputProjection::project)
                .toList();
    }

    public static PublicOutput.Symbol symbol(CodeSymbol symbol) {
        if (symbol == null) {
            return null;
        }
        return new PublicOutput.Symbol(
                symbol.kind().name(),
                symbol.name(),
                SensitiveContentRedactor.redactField(symbol.qualifiedName()),
                SensitiveContentRedactor.redactField(symbol.signature()),
                symbol.startLine(),
                symbol.endLine(),
                symbol.sourceProvider());
    }

    public static PublicOutput.RankedResult rankedResult(ProjectDescriptor project, RankedCandidate ranked, int rank) {
        var candidate = ranked.candidate();
        return new PublicOutput.RankedResult(
                rank,
                SensitiveContentRedactor.redactField(candidate.id()),
                candidate.type().name(),
                PublicProjectPathPolicy.expose(project.rootPath(), candidate.path()),
                SensitiveContentRedactor.redactField(candidate.excerpt()),
                ranked.score(),
                new TreeMap<>(ranked.components()),
                diagnostics(project).texts(ranked.reasons()),
                symbol(candidate.symbol()));
    }

    public static PublicOutput.Search search(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) {
        List<PublicOutput.RankedResult> projected = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            projected.add(rankedResult(project, results.get(index), index + 1));
        }
        return new PublicOutput.Search(project(project), query, limit, explain, durationMs, projected);
    }

    public static PublicOutput.FederatedSearch federatedSearch(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchHit> results) {
        List<PublicOutput.FederatedSearchResult> projected = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            FederatedSearchHit hit = results.get(index);
            projected.add(new PublicOutput.FederatedSearchResult(
                    project(hit.project()), rankedResult(hit.project(), hit.rankedCandidate(), index + 1)));
        }
        return new PublicOutput.FederatedSearch(projects(projects), query, limit, explain, durationMs, projected);
    }

    public static PublicOutput.Context context(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            ContextBundle bundle) {
        ContextBundle exposed = PublicContextPolicy.expose(project, bundle, query);
        return new PublicOutput.Context(
                project(project),
                query,
                explain,
                durationMs,
                exposed.tokenBudget(),
                exposed.estimatedTokens(),
                exposed.items().stream().map(item -> contextItem(project, item)).toList(),
                exposed.excluded(),
                sortedMetadata(exposed.metadata()));
    }

    public static PublicOutput.FederatedContext federatedContext(
            List<ProjectDescriptor> projects,
            String query,
            boolean explain,
            long durationMs,
            FederatedContextBundle bundle) {
        FederatedContextBundle exposed = PublicContextPolicy.expose(projects, bundle, query);
        return new PublicOutput.FederatedContext(
                projects(projects),
                query,
                explain,
                durationMs,
                exposed.tokenBudget(),
                exposed.estimatedTokens(),
                exposed.items().stream()
                        .map(federated -> new PublicOutput.FederatedContextItem(
                                project(federated.project()), contextItem(federated.project(), federated.item())))
                        .toList(),
                exposed.excluded(),
                sortedMetadata(exposed.metadata()));
    }

    public static PublicOutput.SymbolSearch symbolSearch(
            ProjectDescriptor project,
            String query,
            List<com.nexus.index.IndexedSymbol> symbols) {
        return new PublicOutput.SymbolSearch(project(project), query, symbols.stream()
                .map(indexed -> new PublicOutput.IndexedSymbol(
                        PublicProjectPathPolicy.expose(project.rootPath(), java.nio.file.Path.of(indexed.relativePath())),
                        symbol(indexed.symbol())))
                .toList());
    }

    public static PublicOutput.Usages usages(ProjectDescriptor project, String symbol, List<SymbolRelation> relations) {
        return new PublicOutput.Usages(project(project), symbol, relations.stream()
                .map(PublicOutputProjection::relation)
                .toList());
    }

    public static PublicOutput.Relation relation(SymbolRelation relation) {
        return new PublicOutput.Relation(
                relation.kind().name(),
                SensitiveContentRedactor.redactField(relation.source()),
                SensitiveContentRedactor.redactField(relation.target()),
                relation.confidence(),
                relation.sourceProvider());
    }

    public static PublicOutput.IndexStatistics statistics(com.nexus.index.IndexStatistics statistics) {
        return new PublicOutput.IndexStatistics(statistics.files(), statistics.symbols(), statistics.relations());
    }

    public static PublicOutput.Index index(ProjectDescriptor project, IndexingReport report) {
        return new PublicOutput.Index(project(project), new PublicOutput.IndexReport(
                report.scannedFiles(),
                report.changedFiles(),
                report.removedFiles(),
                report.skippedFiles(),
                diagnostics(project).texts(report.diagnostics()),
                report.fullSearchRebuild(),
                report.duration().toMillis(),
                statistics(report.statistics())));
    }

    private static PublicOutput.ContextItem contextItem(ProjectDescriptor project, com.nexus.context.ContextItem item) {
        return new PublicOutput.ContextItem(
                item.type().name(),
                PublicProjectPathPolicy.expose(project.rootPath(), item.path()),
                item.symbol(),
                item.startLine(),
                item.endLine(),
                item.content(),
                item.score(),
                new TreeMap<>(item.scoreComponents()),
                item.reasons(),
                item.estimatedTokens(),
                item.truncated());
    }

    private static PublicDiagnosticPolicy diagnostics(ProjectDescriptor project) {
        return new PublicDiagnosticPolicy(List.of(project.rootPath()));
    }

    /** Trie par clé les maps imbriquées des métadonnées, déjà filtrées par la politique de contexte. */
    private static SortedMap<String, Object> sortedMetadata(Map<String, Object> metadata) {
        SortedMap<String, Object> sorted = new TreeMap<>();
        metadata.forEach((key, value) -> sorted.put(key, sortedValue(value)));
        return sorted;
    }

    private static Object sortedValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), sortedValue(nested)));
            return java.util.Collections.unmodifiableMap(sorted);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PublicOutputProjection::sortedValue).toList();
        }
        return value;
    }
}
