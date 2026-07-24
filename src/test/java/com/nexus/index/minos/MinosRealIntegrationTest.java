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
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in cross-repository replay using the real MINOS Java-24 shaded JAR.
 */
class MinosRealIntegrationTest {

    private static final String JAR_PROPERTY = "nexus.minos.integration.jar";
    private static final String JAVA_PROPERTY = "nexus.minos.integration.java";
    private static final String FIXTURE_PROPERTY = "nexus.minos.integration.fixture";

    @Test
    void realMinosExportFeedsNexusSearch(@TempDir Path temp) throws Exception {
        String configuredJar = System.getProperty(JAR_PROPERTY);
        String configuredJava = System.getProperty(JAVA_PROPERTY);
        String configuredFixture = System.getProperty(FIXTURE_PROPERTY);
        Assumptions.assumeTrue(configuredJar != null && !configuredJar.isBlank(),
                () -> "opt-in: provide -D" + JAR_PROPERTY);
        Assumptions.assumeTrue(configuredJava != null && !configuredJava.isBlank(),
                () -> "opt-in: provide -D" + JAVA_PROPERTY);
        Assumptions.assumeTrue(configuredFixture != null && !configuredFixture.isBlank(),
                () -> "opt-in: provide -D" + FIXTURE_PROPERTY);

        Path minosJar = Path.of(configuredJar).toRealPath();
        Path java24 = Path.of(configuredJava).toRealPath();
        Path fixture = Path.of(configuredFixture).toRealPath();
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        Assumptions.assumeTrue(Files.isRegularFile(scip), () -> "missing SCIP fixture: " + scip);

        Path minosHome = Files.createDirectories(temp.resolve("minos-home"));
        runMinos(java24, minosJar, minosHome,
                "project", "add", fixture.toString(), "--name", "m13-fixture");
        runMinos(java24, minosJar, minosHome,
                "index", "m13-fixture",
                "--scip", scip.toString(),
                "--provider", "scip-typescript",
                "--provider-version", "0.4.0");

        MinosCodeIndexImporter importer = new MinosCodeIndexImporter(
                new MinosCodeIndexImporter.Configuration(
                        minosJar,
                        minosHome,
                        java24.toString(),
                        Duration.ofSeconds(30)));
        CodeIntelligenceSnapshot exported = importer.importIndex(fixture).orElseThrow();
        assertFalse(exported.symbols().isEmpty());
        assertTrue(exported.symbols().stream().anyMatch(symbol ->
                "GreetingPort".equals(symbol.symbol().name())));

        NexusPaths paths = new NexusPaths(temp.resolve("nexus-home"));
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

    private static void runMinos(
            Path java24,
            Path jar,
            Path home,
            String... arguments
    ) throws Exception {
        Path stdout = Files.createTempFile("m13-minos-", ".out");
        Path stderr = Files.createTempFile("m13-minos-", ".err");
        try {
            List<String> command = new java.util.ArrayList<>();
            command.add(java24.toString());
            command.add("-Dminos.home=" + home);
            command.add("-jar");
            command.add(jar.toString());
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new AssertionError("MINOS fixture setup timed out");
            }
            if (process.exitValue() != 0) {
                String error = Files.readString(stderr, StandardCharsets.UTF_8)
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .trim();
                throw new AssertionError("MINOS fixture setup failed: " + error);
            }
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }
}
