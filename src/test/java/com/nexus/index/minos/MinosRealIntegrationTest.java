package com.nexus.index.minos;

import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.SearchService;
import com.nexus.search.SymbolSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in cross-repository replay using the deterministic sandbox prepared by
 * {@code scripts/validate-minos-integration.ps1}.
 */
class MinosRealIntegrationTest {

    private static final String REPLAY_PROPERTY = "nexus.minos.integration.replay";
    private static final Path REPLAY_ROOT = Path.of("target", "m13-replay").toAbsolutePath().normalize();
    private static final Path EXPORT_FILE = REPLAY_ROOT.resolve("minos-export.json");

    @Test
    void realMinosExportFeedsNexusSearch() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(REPLAY_PROPERTY),
                () -> "opt-in: provide -D" + REPLAY_PROPERTY + "=true via the validation script");

        NexusPaths paths = new NexusPaths(REPLAY_ROOT.resolve("nexus-home"));
        Path fixture = REPLAY_ROOT.resolve("fixture").toRealPath();
        Assumptions.assumeTrue(Files.isRegularFile(EXPORT_FILE),
                () -> "missing MINOS export in deterministic replay sandbox");

        String payload = Files.readString(EXPORT_FILE, StandardCharsets.UTF_8);
        CodeIntelligenceSnapshot exported = new MinosCodeIndexImporter().importPayload(fixture, payload);
        assertFalse(exported.symbols().isEmpty());
        assertTrue(exported.symbols().stream().anyMatch(symbol ->
                "GreetingPort".equals(symbol.symbol().name())));

        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(fixture, "m13-fixture");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer(), new MarkdownLanguageAnalyzer()),
                searchIndex);
        indexingService.rebuild(project.id());
        indexRepository.replaceExternalCodeIntelligence(project.id(), exported);

        List<IndexedSymbol> indexedSymbols = indexRepository.findSymbols(project.id());
        assertTrue(indexedSymbols.stream().anyMatch(symbol ->
                "GreetingPort".equals(symbol.symbol().name())
                        && "minos".equals(symbol.symbol().sourceProvider())));

        SearchService searchService = new SearchService(
                List.of(new SymbolSearchStrategy(indexRepository)),
                List.of(),
                new DeterministicContextRanker());
        List<RankedCandidate> results = searchService.search(project, "GreetingPort", 10, true);
        assertTrue(results.stream().anyMatch(result ->
                result.candidate().symbol() != null
                        && "GreetingPort".equals(result.candidate().symbol().name())
                        && "minos".equals(result.candidate().symbol().sourceProvider())));

        System.out.printf(
                "M13 MINOS->NEXUS: symbols=%d, relations=%d, nexus-symbols=%d, search=%d%n",
                exported.symbols().size(), exported.relations().size(), indexedSymbols.size(), results.size());
    }
}
