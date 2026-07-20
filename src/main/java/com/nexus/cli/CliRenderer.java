package com.nexus.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexingReport;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;

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
            writeJson(out, Map.of(
                    "command", "project.add",
                    "project", projectMap(project)));
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

    void renderSearch(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) throws IOException {
        if (json) {
            List<Map<String, Object>> resultMaps = new ArrayList<>();
            for (int index = 0; index < results.size(); index++) {
                RankedCandidate ranked = results.get(index);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rank", index + 1);
                result.put("score", ranked.score());
                result.put("type", ranked.candidate().type().name());
                result.put("path", relativePath(project, ranked.candidate().path()));
                result.put("symbol", symbolMap(ranked.candidate().symbol()));
                result.put("scoreComponents", new TreeMap<>(ranked.components()));
                result.put("reasons", explain ? ranked.reasons() : List.of());
                resultMaps.add(result);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "search");
            payload.put("project", projectMap(project));
            payload.put("query", query);
            payload.put("limit", limit);
            payload.put("explain", explain);
            payload.put("durationMs", durationMs);
            payload.put("results", resultMaps);
            writeJson(out, payload);
            return;
        }

        out.printf("Recherche '%s' : %d résultat(s), %d ms%n", query, results.size(), durationMs);
        for (int index = 0; index < results.size(); index++) {
            RankedCandidate ranked = results.get(index);
            String relativePath = relativePath(project, ranked.candidate().path());
            String target = ranked.candidate().symbol() == null
                    ? relativePath
                    : relativePath + "#" + ranked.candidate().symbol().signature();
            out.printf(
                    "%2d. %.4f %-6s %s%n",
                    index + 1,
                    ranked.score(),
                    ranked.candidate().type(),
                    target);
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
            List<Map<String, Object>> items = new ArrayList<>();
            for (ContextItem item : bundle.items()) {
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
                items.add(itemMap);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "context");
            payload.put("project", projectMap(project));
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
                "Contexte '%s' : %d item(s), %d/%d tokens estimés, %d ms%n",
                query,
                bundle.items().size(),
                bundle.estimatedTokens(),
                bundle.tokenBudget(),
                durationMs);
        for (int index = 0; index < bundle.items().size(); index++) {
            ContextItem item = bundle.items().get(index);
            String target = item.symbol() == null
                    ? item.path().toString()
                    : item.path() + "#" + item.symbol();
            out.printf(
                    "%n[%d] %.4f %-6s %s:%d-%d (%d tokens)%s%n",
                    index + 1,
                    item.score(),
                    item.type(),
                    target,
                    item.startLine(),
                    item.endLine(),
                    item.estimatedTokens(),
                    item.truncated() ? " [TRONQUÉ]" : "");
            if (explain) {
                item.reasons().forEach(reason -> out.println("    - " + reason));
            }
            out.println("-----");
            out.println(item.content());
            out.println("-----");
        }

        if (explain) {
            out.println();
            out.println("Métadonnées : " + bundle.metadata());
            if (!bundle.excluded().isEmpty()) {
                out.println("Exclusions :");
                bundle.excluded().forEach(exclusion -> out.println("  - " + exclusion));
            }
        }
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
        out.printf(
                "Index : %d fichiers, %d symboles, %d relations%n",
                statistics.files(),
                statistics.symbols(),
                statistics.relations());
    }

    void renderUsage() throws IOException {
        List<String> commands = List.of(
                "project add <chemin> [nom] [--json]",
                "project list [--json]",
                "index <id-ou-nom> [--rebuild] [--deep-java] [--json]",
                "search <id-ou-nom> <requête> [--limit N] [--explain] [--json]",
                "context <id-ou-nom> <requête> [--budget N] [--explain] [--json]",
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
                writeJson(err, Map.of(
                        "error", true,
                        "exitCode", exitCode,
                        "message", message));
            } catch (IOException serializationFailure) {
                err.println("Erreur NEXUS : " + message);
            }
            return;
        }
        err.println("Erreur NEXUS : " + message);
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
        return Map.of(
                "files", statistics.files(),
                "symbols", statistics.symbols(),
                "relations", statistics.relations());
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
        out.printf(
                "%s\t%s\t%s\t%s%n",
                project.id(),
                project.name(),
                project.indexStatus(),
                project.rootPath());
    }

    private static String relativePath(ProjectDescriptor project, Path path) {
        return repositoryPath(project.rootPath().relativize(path));
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
