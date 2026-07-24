package com.nexus.index.minos;

import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIndexImporter;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in cross-repository replay using a real, pre-installed MINOS Java-24 shaded JAR.
 */
class MinosRealIntegrationTest {

    private static final String HOME_PROPERTY = "nexus.minos.integration.home";
    private static final String FIXTURE_PROPERTY = "nexus.minos.integration.fixture";

    @Test
    void realMinosExportFeedsNexusSearch() throws Exception {
        String configuredHome = System.getProperty(HOME_PROPERTY);
        String configuredFixture = System.getProperty(FIXTURE_PROPERTY);
        Assumptions.assumeTrue(configuredHome != null && !configuredHome.isBlank(),
                () -> "opt-in: provide -D" + HOME_PROPERTY);
        Assumptions.assumeTrue(configuredFixture != null && !configuredFixture.isBlank(),
                () -> "opt-in: provide -D" + FIXTURE_PROPERTY);

        NexusPaths paths = new NexusPaths(Path.of(configuredHome).toRealPath());
        Path fixture = Path.of(configuredFixture).toRealPath();
        Assumptions.assumeTrue(Files.isRegularFile(paths.minosIntegrationJar()),
                () -> "missing conventional MINOS JAR: " + paths.minosIntegrationJar());
        Assumptions.assumeTrue(Files.isDirectory(paths.minosIntegrationHome()),
                () -> "missing prepared MINOS integration home: " + paths.minosIntegrationHome());

        MinosCodeIndexImporter importer = MinosCodeIndexImporter.fromPaths(paths);
        CodeIntelligenceSnapshot exported = importer.importIndex(fixture).orElseThrow();
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
                searchIndex,
                List.<CodeIndexImporter>of(importer));
        indexingService.rebuild(project.id());

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
