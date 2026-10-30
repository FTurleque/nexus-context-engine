package com.nexus.exposure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextItem;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.IndexingReport;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.SearchCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicOutputProjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GITHUB_TOKEN = "ghp_" + "a".repeat(36);

    @TempDir
    Path root;

    @Test
    void searchResultCarriesRelativePathRedactedExcerptIdAndSortedComponents() throws Exception {
        ProjectDescriptor project = project("demo");
        RankedCandidate ranked = ranked(project, components("path", "lexical", "symbol"));

        PublicOutput.Search search = PublicOutputProjection.search(project, "needle", 10, true, 3, List.of(ranked));

        PublicOutput.RankedResult result = search.results().getFirst();
        assertEquals(1, result.rank());
        assertEquals("src/main/java/App.java", result.path());
        assertEquals("symbol:src/main/java/App.java:demo.App", result.id());
        assertFalse(result.excerpt().contains("hunter2"), result.excerpt());
        assertEquals(List.of("lexical", "path", "symbol"), List.copyOf(result.scoreComponents().keySet()));
        assertFalse(JSON.writeValueAsString(search.project()).contains("rootPath"));
        assertEquals(List.of(), divergences(PublicOutput.Search.class, search));
    }

    @Test
    void scoreComponentsSerializeInKeyOrderWhateverTheInsertionOrder() throws Exception {
        ProjectDescriptor project = project("demo");
        String first = JSON.writeValueAsString(PublicOutputProjection.rankedResult(
                project, ranked(project, components("symbol", "path", "lexical")), 1));
        String second = JSON.writeValueAsString(PublicOutputProjection.rankedResult(
                project, ranked(project, components("lexical", "symbol", "path")), 1));

        assertEquals(first, second);
        assertTrue(first.contains("\"scoreComponents\":{\"lexical\":1.0,\"path\":1.0,\"symbol\":1.0}"), first);
    }

    @Test
    void symbolMetadataFromExternalProducersIsRedacted() throws Exception {
        ProjectDescriptor project = project("demo");
        for (String provider : List.of("scip", "minos", "jdt")) {
            CodeSymbol symbol = new CodeSymbol(SymbolKind.TYPE, "TOKEN",
                    "demo.App.TOKEN password = \"hunter2hunter2\"",
                    "String TOKEN = \"" + GITHUB_TOKEN + "\" password = \"hunter2hunter2\"", 3, 3, provider);
            String json = JSON.writeValueAsString(List.of(
                    PublicOutputProjection.symbol(symbol),
                    PublicOutputProjection.symbolSearch(project, "TOKEN",
                            List.of(new IndexedSymbol("src/App.java", symbol))),
                    PublicOutputProjection.usages(project, "TOKEN", List.of(new SymbolRelation(
                            RelationKind.REFERENCES, "demo.App password = \"hunter2hunter2\"",
                            "demo." + GITHUB_TOKEN, 1.0d, provider)))));

            assertFalse(json.contains(GITHUB_TOKEN), json);
            assertFalse(json.contains("hunter2hunter2"), json);
        }
    }

    @Test
    void contextIsExposedWithRelativePathsSortedComponentsAndSortedMetadata() throws Exception {
        ProjectDescriptor project = project("demo");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("zeta", Map.of("b", 2, "a", 1));
        metadata.put("alpha", 1);
        ContextBundle bundle = new ContextBundle(List.of(contextItem(project)), 100, 10, List.of(), metadata);

        PublicOutput.Context context = PublicOutputProjection.context(project, "needle", true, 5, bundle);

        assertEquals("src/main/java/App.java", context.items().getFirst().path());
        assertEquals(List.of("lexical", "path", "symbol"),
                List.copyOf(context.items().getFirst().scoreComponents().keySet()));
        assertEquals(List.of("alpha", "zeta"), List.copyOf(context.metadata().keySet()));
        assertTrue(JSON.writeValueAsString(context).contains("\"zeta\":{\"a\":1,\"b\":2}"));
        assertEquals(List.of(), divergences(PublicOutput.Context.class, context));
    }

    @Test
    void federatedResultsKeepProjectProvenanceAndContract() throws Exception {
        ProjectDescriptor first = project("first");
        PublicOutput.FederatedSearch search = PublicOutputProjection.federatedSearch(List.of(first), "needle", 5,
                false, 1, List.of(new FederatedSearchHit(first, ranked(first, components("path")))));
        PublicOutput.FederatedContext context = PublicOutputProjection.federatedContext(List.of(first), "needle",
                false, 1, new FederatedContextBundle(List.of(new FederatedContextItem(first, contextItem(first))),
                        100, 10, List.of(), Map.of()));

        assertEquals("first", search.results().getFirst().project().name());
        assertEquals("src/main/java/App.java", context.items().getFirst().item().path());
        assertEquals(List.of(), divergences(PublicOutput.FederatedSearch.class, search));
        assertEquals(List.of(), divergences(PublicOutput.FederatedContext.class, context));
    }

    @Test
    void indexReportFiltersDiagnosticPaths() {
        ProjectDescriptor project = project("demo");
        IndexingReport report = new IndexingReport(project.id(), 3, 2, 0, true, new IndexStatistics(3, 1, 0),
                Duration.ofMillis(7), 1, List.of(root.resolve("secret").toString()), Map.of());

        PublicOutput.Index index = PublicOutputProjection.index(project, report);

        assertEquals(7, index.report().durationMs());
        assertFalse(index.report().diagnostics().getFirst().contains(root.toString()));
    }

    @Test
    void schemaReportsAddedRemovedAndReorderedKeys() {
        Map<String, Object> symbol = new LinkedHashMap<>();
        for (String key : PublicOutputSchema.keys(PublicOutput.Symbol.class)) {
            symbol.put(key, 1);
        }
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Symbol.class, symbol, List.of()));

        Map<String, Object> extra = new LinkedHashMap<>(symbol);
        extra.put("rawSignature", "x");
        Map<String, Object> missing = new LinkedHashMap<>(symbol);
        missing.remove("signature");
        Map<String, Object> enveloped = new LinkedHashMap<>();
        enveloped.put(PublicOutputSchema.CLI_COMMAND_FIELD, "search");
        enveloped.putAll(symbol);

        assertEquals(1, PublicOutputSchema.divergences(PublicOutput.Symbol.class, extra, List.of()).size());
        assertEquals(1, PublicOutputSchema.divergences(PublicOutput.Symbol.class, missing, List.of()).size());
        assertEquals(1, PublicOutputSchema.divergences(PublicOutput.Symbol.class, enveloped, List.of()).size());
        assertEquals(List.of(), PublicOutputSchema.divergences(PublicOutput.Symbol.class, enveloped,
                List.of(PublicOutputSchema.CLI_COMMAND_FIELD)));
        assertEquals(1, PublicOutputSchema.divergences(PublicOutput.Symbol.class, List.of(), List.of()).size());
    }

    private static List<String> divergences(Class<? extends Record> type, Object value) throws Exception {
        return PublicOutputSchema.divergences(type, JSON.readValue(JSON.writeValueAsString(value), Object.class),
                List.of());
    }

    private ProjectDescriptor project(String name) {
        return new ProjectDescriptor(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                name, root, ProjectSourceType.LOCAL, Set.of("java", "c"), Set.of(), null, IndexStatus.READY);
    }

    private RankedCandidate ranked(ProjectDescriptor project, Map<String, Double> components) {
        CodeSymbol symbol = new CodeSymbol(SymbolKind.CLASS, "App", "demo.App", "class App", 1, 3, "java");
        SearchCandidate candidate = new SearchCandidate("symbol:src/main/java/App.java:demo.App",
                CandidateType.SYMBOL, project.rootPath().resolve("src/main/java/App.java"), symbol,
                "class App { String password = \"hunter2\"; }", Map.of());
        return new RankedCandidate(candidate, 1.0d, components, List.of("lexical match"));
    }

    private ContextItem contextItem(ProjectDescriptor project) {
        return new ContextItem(CandidateType.FILE, project.rootPath().resolve("src/main/java/App.java"), null,
                1, 3, "class App {}", 0.5d, components("symbol", "path", "lexical"), List.of(), 10, false);
    }

    private static Map<String, Double> components(String... keys) {
        Map<String, Double> components = new LinkedHashMap<>();
        for (String key : keys) {
            components.put(key, 1.0d);
        }
        return components;
    }
}
