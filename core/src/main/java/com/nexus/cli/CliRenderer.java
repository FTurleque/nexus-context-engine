package com.nexus.cli;


import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexus.context.ContextBundle;
import com.nexus.context.FederatedContextBundle;
import com.nexus.exposure.PublicOutput;
import com.nexus.exposure.PublicOutputProjection;
import com.nexus.exposure.PublicOutputSchema;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexingReport;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.FederatedSearchHit;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rendu CLI. Le JSON sérialise les structures de {@link PublicOutputProjection} précédées de la
 * clé {@link PublicOutputSchema#CLI_COMMAND_FIELD} ; le rendu humain lit les mêmes structures.
 */
final class CliRenderer {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() { };

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
            writeJson(out, command("project.add", Map.of("project", PublicOutputProjection.project(project))));
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
            writeJson(out, command("project.list", Map.of("projects", PublicOutputProjection.projects(sorted))));
            return;
        }
        sorted.forEach(this::printProject);
    }

    void renderIndex(ProjectDescriptor project, IndexingReport report) throws IOException {
        PublicOutput.Index index = PublicOutputProjection.index(project, report);
        if (json) {
            writeJson(out, command("index", index));
            return;
        }

        PublicOutput.IndexReport projected = index.report();
        out.printf(
                "Projet %s : %d scannés, %d modifiés, %d supprimés, %d ignorés, %d fichiers / %d symboles / %d relations, %d ms%s%n",
                human(project.name()),
                projected.scannedFiles(),
                projected.changedFiles(),
                projected.removedFiles(),
                projected.skippedFiles(),
                projected.statistics().files(),
                projected.statistics().symbols(),
                projected.statistics().relations(),
                projected.durationMs(),
                projected.fullSearchRebuild() ? " (reconstruction complète)" : "");
        projected.diagnostics().forEach(diagnostic -> out.println("  - " + human(diagnostic)));
    }

    void renderMinosImport(ProjectDescriptor project, CodeIntelligenceSnapshot snapshot) throws IOException {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("command", "minos-import");
            payload.put("project", PublicOutputProjection.project(project));
            payload.put("sourceProvider", snapshot.sourceProvider());
            payload.put("symbols", snapshot.symbols().size());
            payload.put("relations", snapshot.relations().size());
            writeJson(out, payload);
            return;
        }
        out.printf(
                "MINOS importé pour %s : %d symbole(s), %d relation(s)%n",
                human(project.name()), snapshot.symbols().size(), snapshot.relations().size());
    }

    void renderSearch(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) throws IOException {
        PublicOutput.Search search = PublicOutputProjection.search(project, query, limit, explain, durationMs, results);
        if (json) {
            writeJson(out, command("search", search));
            return;
        }
        out.printf("Recherche '%s' : %d résultat(s), %d ms%n", human(query), search.results().size(), durationMs);
        for (PublicOutput.RankedResult result : search.results()) {
            out.printf("%2d. %.4f %-6s %s%n", result.rank(), result.score(), result.type(), human(target(result)));
            printReasons(explain, result.reasons());
        }
    }

    void renderFederatedSearch(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchHit> results) throws IOException {
        PublicOutput.FederatedSearch search =
                PublicOutputProjection.federatedSearch(projects, query, limit, explain, durationMs, results);
        if (json) {
            writeJson(out, command("search-federated", search));
            return;
        }
        out.printf("Recherche fédérée '%s' : %d résultat(s), %d ms%n", human(query), search.results().size(), durationMs);
        for (PublicOutput.FederatedSearchResult hit : search.results()) {
            PublicOutput.RankedResult result = hit.result();
            out.printf("%2d. %.4f %-6s [%s] %s%n",
                    result.rank(), result.score(), result.type(), human(hit.project().name()), human(target(result)));
            printReasons(explain, result.reasons());
        }
    }

    void renderContext(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            ContextBundle bundle) throws IOException {
        PublicOutput.Context context = PublicOutputProjection.context(project, query, explain, durationMs, bundle);
        if (json) {
            writeJson(out, command("context", context));
            return;
        }
        printContextHeader(query, durationMs, context.items().size(), context.estimatedTokens(), context.tokenBudget());
        for (int index = 0; index < context.items().size(); index++) {
            printContextItem(index + 1, null, context.items().get(index), explain);
        }
        printContextDiagnostics(context.metadata(), context.excluded(), explain);
    }

    void renderFederatedContext(
            List<ProjectDescriptor> projects,
            String query,
            boolean explain,
            long durationMs,
            FederatedContextBundle bundle) throws IOException {
        PublicOutput.FederatedContext context =
                PublicOutputProjection.federatedContext(projects, query, explain, durationMs, bundle);
        if (json) {
            writeJson(out, command("context-federated", context));
            return;
        }
        out.printf(
                "Contexte fédéré '%s' : %d item(s), %d/%d tokens estimés, %d ms%n",
                human(query), context.items().size(), context.estimatedTokens(), context.tokenBudget(), durationMs);
        for (int index = 0; index < context.items().size(); index++) {
            PublicOutput.FederatedContextItem federated = context.items().get(index);
            printContextItem(index + 1, federated.project().name(), federated.item(), explain);
        }
        printContextDiagnostics(context.metadata(), context.excluded(), explain);
    }

    void renderInspect(ProjectDescriptor project, IndexStatistics statistics) throws IOException {
        if (json) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("project", PublicOutputProjection.project(project));
            payload.put("index", PublicOutputProjection.statistics(statistics));
            writeJson(out, command("inspect", payload));
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
        message = com.nexus.security.PublicDiagnosticPolicy.internal().text(message);
        if (json) {
            try {
                writeJson(err, Map.of("error", true, "exitCode", exitCode, "message", message));
            } catch (IOException serializationFailure) {
                err.println("Erreur NEXUS : " + human(message));
            }
            return;
        }
        err.println("Erreur NEXUS : " + human(message));
    }

    private static String target(PublicOutput.RankedResult result) {
        return result.symbol() == null ? result.path() : result.path() + "#" + result.symbol().signature();
    }

    private void printReasons(boolean explain, List<String> reasons) {
        if (explain) {
            reasons.forEach(reason -> out.println("    - " + human(reason)));
        }
    }

    private void printContextHeader(String query, long durationMs, int count, int estimatedTokens, int tokenBudget) {
        out.printf("Contexte '%s' : %d item(s), %d/%d tokens estimés, %d ms%n",
                human(query), count, estimatedTokens, tokenBudget, durationMs);
    }

    private void printContextItem(int rank, String projectName, PublicOutput.ContextItem item, boolean explain) {
        String target = human(item.symbol() == null
                ? item.path()
                : item.path() + "#" + item.symbol());
        String projectPrefix = projectName == null ? "" : "[" + human(projectName) + "] ";
        out.printf("%n[%d] %.4f %-6s %s%s:%d-%d (%d tokens)%s%n",
                rank, item.score(), item.type(), projectPrefix, target,
                item.startLine(), item.endLine(), item.estimatedTokens(),
                item.truncated() ? " [TRONQUÉ]" : "");
        printReasons(explain, item.reasons());
        out.println("-----");
        out.println(human(item.content()));
        out.println("-----");
    }

    private void printContextDiagnostics(Map<String, Object> metadata, List<String> excluded, boolean explain) {
        if (!explain) {
            return;
        }
        out.println();
        out.println("Métadonnées : " + human(metadata));
        if (!excluded.isEmpty()) {
            out.println("Exclusions :");
            excluded.forEach(exclusion -> out.println("  - " + human(exclusion)));
        }
    }

    private void printProject(ProjectDescriptor project) {
        out.printf("%s\t%s\t%s\t%s%n",
                project.id(), human(project.name()), project.indexStatus(), "[repository]");
    }

    private static String human(Object value) {
        return TerminalTextSanitizer.sanitize(String.valueOf(value));
    }

    /** Enveloppe CLI : la clé de commande, puis les clés de la structure publique, inchangées. */
    private Map<String, Object> command(String command, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PublicOutputSchema.CLI_COMMAND_FIELD, command);
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> payload.put(String.valueOf(key), nested));
        } else {
            payload.putAll(objectMapper.convertValue(value, OBJECT));
        }
        return payload;
    }

    private void writeJson(PrintStream stream, Object value) throws IOException {
        objectMapper.writeValue(stream, value);
        stream.println();
        stream.flush();
    }
}
