package com.nexus.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextItem;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexingReport;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.FederatedSearchHit;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class CliRenderer {

    private final PrintStream out;
    private final PrintStream err;
    private final boolean json;
    private final ObjectMapper objectMapper;

    CliRenderer(PrintStream out, PrintStream err, boolean json) {
        this.out = out;
        this.err = err;
        this.json = json;
        this.objectMapper = new ObjectMapper()
                .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    boolean json() {
        return json;
    }

    void renderProject(ProjectDescriptor project) throws IOException {
        if (json) {
            writeJson(out, Map.of("command", "project.add", "project", projectMap(project)));
            return;
        }
        printProject(project);
    }

    void renderProjects(List<ProjectDescriptor> projects) throws IOException {
        List<ProjectDescriptor> sorted = projects.stream()
                .sorted(Comparator.comparing(ProjectDescriptor::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(project -> project.id().toString()))
                .toList();
        if (json) {
            writeJson(out, Map.of(
                    "command", "project.list",
                    "projects", sorted.stream().map(this::projectMap).toList()));
            return;
        }
        sorted.forEach(this::printProject);
    }

    void renderIndex(ProjectDescriptor project, IndexingReport report) throws IOException {
        if (json) {
            Map<String, Object> reportMap = new LinkedHashMap<>();
            reportMap.put("scannedFiles", report.scannedFiles());
            reportMap.put("changedFiles", report.changedFiles());
            reportMap.put("removedFiles", report.removedFiles());
            reportMap.put("skippedFiles", report.skippedFiles());
            reportMap.put("diagnostics", report.diagnostics());
            reportMap.put("fullSearchRebuild", report.fullSearchRebuild());
            reportMap.put("durationMs", report.duration().toMillis());
            reportMap.put("statistics", statisticsMap(report.statistics()));
            writeJson(out, Map.of(
                    "command", "index",
                    "project", projectMap(project),
                    "report", reportMap));
            return;
        }

        out.printf(
                "Projet %s : %d scannés, %d modifiés, %d supprimés, %d ignorés, %d fichiers / %d symboles / %d relations, %d ms%s%n",
                project.name(),
                report.scannedFiles(),
                report.changedFiles(),
                report.removedFiles(),
                report.skippedFiles(),
                report.statistics().files(),
                report.statistics().symbols(),
                report.statistics().relations(),
                report.duration().toMillis(),
                report.fullSearchRebuild() ? " (reconstruction complète)" : "");
        report.diagnostics().forEach(diagnostic -> out.println("  - " + diagnostic));
    }

    void renderMinosImport(ProjectDescriptor project, CodeIntelligenceSnapshot snapshot) throws IOException {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "minos-import");
            payload.put("project", projectMap(project));
            payload.put("sourceProvider", snapshot.sourceProvider());
            payload.put("symbols", snapshot.symbols().size());
            payload.put("relations", snapshot.relations().size());
            writeJson(out, payload);
            return;
        }
        out.printf(
                "MINOS importé pour %s : %d symbole(s), %d relation(s)%n",
                project.name(), snapshot.symbols().size(), snapshot.relations().size());
    }

    void renderSearch(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) throws IOException {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "search");
            payload.put("project", projectMap(project));
            payload.put("query", query);
            payload.put("limit", limit);
            payload.put("explain", explain);
            payload.put("durationMs", durationMs);
            payload.put("results", rankedResults(project, results, explain));
            writeJson(out, payload);
            return;
        }
        printSearchResults(project, query, explain, durationMs, results);
    }

    void renderFederatedSearch(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchHit> results) throws IOException {
        if (json) {
            List<Map<String, Object>> resultMaps = new ArrayList<>();
            for (int index = 0; index < results.size(); index++) {
                FederatedSearchHit hit = results.get(index);
                Map<String, Object> result = rankedResult(
                        hit.project(), hit.rankedCandidate(), index + 1, explain);
                result.put("project", projectMap(hit.project()));
                resultMaps.add(result);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "search-federated");
            payload.put("projects", projects.stream().map(this::projectMap).toList());
            payload.put("query", query);
            payload.put("limit", limit);
            payload.put("explain", explain);
            payload.put("durationMs", durationMs);
            payload.put("results", resultMaps);
            writeJson(out, payload);
            return;
        }
        out.printf("Recherche fédérée '%s' : %d résultat(s), %d ms%n", query, results.size(), durationMs);
        for (int index = 0; index < results.size(); index++) {
            FederatedSearchHit hit = results.get(index);
            RankedCandidate ranked = hit.rankedCandidate();
            String relativePath = relativePath(hit.project(), ranked.candidate().path());
            String target = ranked.candidate().symbol() == null
                    ? relativePath
                    : relativePath + "#" + ranked.candidate().symbol().signature();
            out.printf("%2d. %.4f %-6s [%s] %s%n",
                    index + 1, ranked.score(), ranked.candidate().type(), hit.project().name(), target);
            if (explain) {
                ranked.reasons().forEach(reason -> out.println("    - " + reason));
            }
        }
    }

    void renderContext(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            ContextBundle bundle) throws IOException {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "context");
            payload.put("project", projectMap(project));
            payload.put("query", query);
            payload.put("durationMs", durationMs);
            payload.put("tokenBudget", bundle.tokenBudget());
            payload.put("estimatedTokens", bundle.estimatedTokens());
            payload.put("items", bundle.items().stream().map(item -> contextItemMap(item, explain)).toList());
            payload.put("excluded", explain ? bundle.excluded() : List.of());
            payload.put("metadata", new TreeMap<>(bundle.metadata()));
            writeJson(out, payload);
            return;
        }
        printContextHeader(query, durationMs, bundle.items().size(), bundle.estimatedTokens(), bundle.tokenBudget());
        for (int index = 0; index < bundle.items().size(); index++) {
            printContextItem(index + 1, null, bundle.items().get(index), explain);
        }
        printContextDiagnostics(bundle.metadata(), bundle.excluded(), explain);
    }

    void renderFederatedContext(
            List<ProjectDescriptor> projects,
            String query,
            boolean explain,
            long durationMs,
            FederatedContextBundle bundle) throws IOException {
        if (json) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (FederatedContextItem federated : bundle.items()) {
                Map<String, Object> item = contextItemMap(federated.item(), explain);
                item.put("project", projectMap(federated.project()));
                items.add(item);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "context-federated");
            payload.put("projects", projects.stream().map(this::projectMap).toList());
            payload.put("query", query);
            payload.put("durationMs", durationMs);
            payload.put("tokenBudget", bundle.tokenBudget());
            payload.put("estimatedTokens", bundle.estimatedTokens());
            payload.put("items", items);
            payload.put("excluded", explain ? bundle.excluded() : List.of());
            payload.put("metadata", new TreeMap<>(bundle.metadata()));
            writeJson(out, payload);
            return;
        }
        out.printf(
                "Contexte fédéré '%s' : %d item(s), %d/%d tokens estimés, %d ms%n",
                query, bundle.items().size(), bundle.estimatedTokens(), bundle.tokenBudget(), durationMs);
        for (int index = 0; index < bundle.items().size(); index++) {
            FederatedContextItem federated = bundle.items().get(index);
            printContextItem(index + 1, federated.project().name(), federated.item(), explain);
        }
        printContextDiagnostics(bundle.metadata(), bundle.excluded(), explain);
    }

    void renderInspect(ProjectDescriptor project, IndexStatistics statistics) throws IOException {
        if (json) {
            writeJson(out, Map.of(
                    "command", "inspect",
                    "project", projectMap(project),
                    "index", statisticsMap(statistics)));
            return;
        }
        printProject(project);
        out.printf("Index : %d fichiers, %d symboles, %d relations%n",
                statistics.files(), statistics.symbols(), statistics.relations());
    }

    void renderUsage() throws IOException {
        List<String> commands = List.of(
                "project add <chemin> [nom] [--json]",
                "project list [--json]",
                "index <id-ou-nom> [--rebuild] [--deep-java] [--json]",
                "minos-import <id-ou-nom> < export-minos.json [--json]",
                "search <id-ou-nom> <requête> [--limit N] [--explain] [--json]",
                "search-federated <projet1,projet2,...> <requête> [--limit N] [--explain] [--json]",
                "context <id-ou-nom> <requête> [--budget N] [--explain] [--json]",
                "context-federated <projet1,projet2,...> <requête> [--budget N] [--explain] [--json]",
                "inspect <id-ou-nom> [--json]",
                "--help",
                "--version");
        if (json) {
            writeJson(out, Map.of("command", "help", "commands", commands));
            return;
        }
        out.println("NEXUS Context Engine");
        out.println();
        out.println("Commandes disponibles :");
        commands.forEach(command -> out.println("  nexus " + command));
    }

    void renderVersion(String version) throws IOException {
        if (json) {
            writeJson(out, Map.of("command", "version", "version", version));
            return;
        }
        out.println("NEXUS Context Engine " + version);
    }

    void renderError(String message, int exitCode) {
        if (json) {
            try {
                writeJson(err, Map.of("error", true, "exitCode", exitCode, "message", message));
            } catch (IOException serializationFailure) {
                err.println("Erreur NEXUS : " + message);
            }
            return;
        }
        err.println("Erreur NEXUS : " + message);
    }

    private List<Map<String, Object>> rankedResults(
            ProjectDescriptor project,
            List<RankedCandidate> results,
            boolean explain) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            maps.add(rankedResult(project, results.get(index), index + 1, explain));
        }
        return maps;
    }

    private Map<String, Object> rankedResult(
            ProjectDescriptor project,
            RankedCandidate ranked,
            int rank,
            boolean explain) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rank", rank);
        result.put("score", ranked.score());
        result.put("type", ranked.candidate().type().name());
        result.put("path", relativePath(project, ranked.candidate().path()));
        result.put("symbol", symbolMap(ranked.candidate().symbol()));
        result.put("scoreComponents", new TreeMap<>(ranked.components()));
        result.put("reasons", explain ? ranked.reasons() : List.of());
        return result;
    }

    private void printSearchResults(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) {
        out.printf("Recherche '%s' : %d résultat(s), %d ms%n", query, results.size(), durationMs);
        for (int index = 0; index < results.size(); index++) {
            RankedCandidate ranked = results.get(index);
            String relativePath = relativePath(project, ranked.candidate().path());
            String target = ranked.candidate().symbol() == null
                    ? relativePath
                    : relativePath + "#" + ranked.candidate().symbol().signature();
            out.printf("%2d. %.4f %-6s %s%n",
                    index + 1, ranked.score(), ranked.candidate().type(), target);
            if (explain) {
                ranked.reasons().forEach(reason -> out.println("    - " + reason));
            }
        }
    }

    private Map<String, Object> contextItemMap(ContextItem item, boolean explain) {
        Map<String, Object> itemMap = new LinkedHashMap<>();
        itemMap.put("type", item.type().name());
        itemMap.put("path", repositoryPath(item.path()));
        itemMap.put("symbol", item.symbol());
        itemMap.put("startLine", item.startLine());
        itemMap.put("endLine", item.endLine());
        itemMap.put("content", item.content());
        itemMap.put("score", item.score());
        itemMap.put("scoreComponents", new TreeMap<>(item.scoreComponents()));
        itemMap.put("reasons", explain ? item.reasons() : List.of());
        itemMap.put("estimatedTokens", item.estimatedTokens());
        itemMap.put("truncated", item.truncated());
        return itemMap;
    }

    private void printContextHeader(String query, long durationMs, int count, int estimatedTokens, int tokenBudget) {
        out.printf("Contexte '%s' : %d item(s), %d/%d tokens estimés, %d ms%n",
                query, count, estimatedTokens, tokenBudget, durationMs);
    }

    private void printContextItem(int rank, String projectName, ContextItem item, boolean explain) {
        String target = item.symbol() == null ? item.path().toString() : item.path() + "#" + item.symbol();
        String projectPrefix = projectName == null ? "" : "[" + projectName + "] ";
        out.printf("%n[%d] %.4f %-6s %s%s:%d-%d (%d tokens)%s%n",
                rank, item.score(), item.type(), projectPrefix, target,
                item.startLine(), item.endLine(), item.estimatedTokens(),
                item.truncated() ? " [TRONQUÉ]" : "");
        if (explain) {
            item.reasons().forEach(reason -> out.println("    - " + reason));
        }
        out.println("-----");
        out.println(item.content());
        out.println("-----");
    }

    private void printContextDiagnostics(Map<String, Object> metadata, List<String> excluded, boolean explain) {
        if (!explain) {
            return;
        }
        out.println();
        out.println("Métadonnées : " + metadata);
        if (!excluded.isEmpty()) {
            out.println("Exclusions :");
            excluded.forEach(exclusion -> out.println("  - " + exclusion));
        }
    }

    private Map<String, Object> projectMap(ProjectDescriptor project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id().toString());
        map.put("name", project.name());
        map.put("rootPath", repositoryPath(project.rootPath()));
        map.put("sourceType", project.sourceType().name());
        map.put("languages", project.languages().stream().sorted().toList());
        map.put("technologies", project.technologies().stream().sorted().toList());
        map.put("lastIndexedAt", project.lastIndexedAt() == null ? null : project.lastIndexedAt().toString());
        map.put("indexStatus", project.indexStatus().name());
        return map;
    }

    private static Map<String, Object> statisticsMap(IndexStatistics statistics) {
        return Map.of("files", statistics.files(), "symbols", statistics.symbols(), "relations", statistics.relations());
    }

    private static Map<String, Object> symbolMap(CodeSymbol symbol) {
        if (symbol == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", symbol.kind().name());
        map.put("name", symbol.name());
        map.put("qualifiedName", symbol.qualifiedName());
        map.put("signature", symbol.signature());
        map.put("startLine", symbol.startLine());
        map.put("endLine", symbol.endLine());
        return map;
    }

    private void printProject(ProjectDescriptor project) {
        out.printf("%s\t%s\t%s\t%s%n",
                project.id(), project.name(), project.indexStatus(), project.rootPath());
    }

    private static String relativePath(ProjectDescriptor project, Path path) {
        Path root = project.rootPath().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? repositoryPath(root.relativize(normalized))
                : repositoryPath(path);
    }

    private static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private void writeJson(PrintStream stream, Object value) throws IOException {
        objectMapper.writeValue(stream, value);
        stream.println();
        stream.flush();
    }
}
