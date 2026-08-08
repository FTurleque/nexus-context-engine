package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.markdown.MarkdownLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.SearchDocument;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexingServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesIncrementallyAndPropagatesChangesAndDeletions() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path sourceFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;
                import java.util.List;
                class App {
                    void run() {}
                }
                """);

        Path generatedFile = projectRoot.resolve("target/generated/Generated.java");
        Files.createDirectories(generatedFile.getParent());
        Files.writeString(generatedFile, "class Generated {}\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry registry = new ProjectRegistry(projectRepository);
        ProjectDescriptor project = registry.register(projectRoot, "demo");

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths));

        IndexingReport first = service.index(project.id());
        assertEquals(1, first.scannedFiles());
        assertEquals(1, first.changedFiles());
        assertEquals(0, first.removedFiles());
        assertTrue(first.fullSearchRebuild());
        assertEquals(new IndexStatistics(1, 2, 1), first.statistics());
        assertEquals(1, luceneDocumentCount(paths, project));
        assertEquals(IndexStatus.READY, registry.get(project.id()).indexStatus());

        IndexingReport second = service.index(project.id());
        assertEquals(0, second.changedFiles());
        assertEquals(0, second.removedFiles());
        assertFalse(second.fullSearchRebuild());
        assertEquals(new IndexStatistics(1, 2, 1), second.statistics());

        Files.writeString(sourceFile, """
                package demo;
                import java.util.List;
                class App {
                    void run() {}
                    void stop() {}
                }
                """);

        IndexingReport third = service.index(project.id());
        assertEquals(1, third.changedFiles());
        assertEquals(new IndexStatistics(1, 3, 1), third.statistics());
        assertEquals(1, luceneDocumentCount(paths, project));

        Files.delete(sourceFile);
        IndexingReport fourth = service.index(project.id());
        assertEquals(0, fourth.scannedFiles());
        assertEquals(0, fourth.changedFiles());
        assertEquals(1, fourth.removedFiles());
        assertEquals(new IndexStatistics(0, 0, 0), fourth.statistics());
        assertEquals(0, luceneDocumentCount(paths, project));
    }

    @Test
    void keepsSkillsCanonicalInSqliteButPurgesThemFromGenericLuceneIndex() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("skills-project"));
        Path sourceFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "package demo; class App {}\n");
        Path skillFile = projectRoot.resolve(".agents/skills/testing/SKILL.md");
        Files.createDirectories(skillFile.getParent());
        Files.writeString(skillFile, """
                ---
                name: testing
                description: Run focused tests for Java changes.
                ---
                # Testing skill
                """);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("skills-nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "skills-demo");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer(), new MarkdownLanguageAnalyzer()),
                searchIndex);

        IndexingReport first = service.index(project.id());
        assertEquals(2, first.statistics().files());
        assertEquals(1, luceneDocumentCount(paths, project));

        // Simule un document SKILL laissé dans Lucene par une ancienne version.
        searchIndex.applyChanges(
                project.id(),
                List.of(new SearchDocument(
                        ".agents/skills/testing/SKILL.md",
                        "markdown",
                        FileCategory.SKILL,
                        "legacy skill document",
                        List.of())),
                Set.of());
        assertEquals(2, luceneDocumentCount(paths, project));

        IndexingReport second = service.index(project.id());
        assertEquals(0, second.changedFiles());
        assertEquals(0, second.removedFiles());
        assertEquals(2, second.statistics().files());
        assertEquals(1, luceneDocumentCount(paths, project));
    }

    @Test
    void mergesExternalCodeIntelligenceAndPurgesItWhenTheIndexDisappears() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("external-index-project"));
        Path sourceFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;
                class App {
                    void run() {}
                }
                """);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("external-index-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "external-demo");
        boolean[] available = {true};
        String provider = "test-external";
        CodeIndexImporter importer = new CodeIndexImporter() {
            @Override
            public String sourceProvider() {
                return provider;
            }

            @Override
            public Optional<CodeIntelligenceSnapshot> importIndex(Path root) {
                if (!available[0]) {
                    return Optional.empty();
                }
                return Optional.of(new CodeIntelligenceSnapshot(
                        provider,
                        List.of(
                                new IndexedSymbol(
                                        "src/main/java/demo/App.java",
                                        new CodeSymbol(
                                                SymbolKind.METHOD,
                                                "run",
                                                "external:demo.App#run()",
                                                "run()",
                                                3,
                                                3,
                                                provider)),
                                new IndexedSymbol(
                                        "src/main/java/demo/App.java",
                                        new CodeSymbol(
                                                SymbolKind.TYPE,
                                                "ExternalOnly",
                                                "external:demo.ExternalOnly",
                                                "ExternalOnly",
                                                4,
                                                4,
                                                provider))),
                        List.of(new IndexedRelation(
                                "src/main/java/demo/App.java",
                                new SymbolRelation(
                                        RelationKind.REFERENCES,
                                        "src/main/java/demo/App.java",
                                        "external:demo.Dependency",
                                        0.95d,
                                        provider)))));
            }
        };

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths),
                List.of(importer));

        IndexingReport enriched = service.index(project.id());
        // Persistence provenance-aware (P1) : le symbole `run` du provider externe n'est plus
        // avalé par le `run` embarqué équivalent — chaque provenance conserve sa ligne. On a donc
        // 2 symboles embarqués (App, run) + 2 symboles externes (run, ExternalOnly) = 4 au total.
        assertEquals(new IndexStatistics(1, 4, 1), enriched.statistics());
        assertEquals(2, indexRepository.findSymbols(project.id()).stream()
                .filter(indexed -> indexed.symbol().sourceProvider().equals(provider))
                .count());
        assertEquals(provider, indexRepository.findRelations(project.id()).getFirst().sourceProvider());

        available[0] = false;
        IndexingReport withoutExternalIndex = service.index(project.id());
        assertEquals(0, withoutExternalIndex.changedFiles());
        assertEquals(new IndexStatistics(1, 2, 0), withoutExternalIndex.statistics());
        assertTrue(indexRepository.findSymbols(project.id()).stream()
                .noneMatch(indexed -> indexed.symbol().sourceProvider().equals(provider)));
    }

    @Test
    void runsActiveProviderOnlyOnDeepIndexAndPurgesItAfterJavaChanges() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("active-provider-project"));
        Path sourceFile = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package demo;
                class App {
                    void run() {}
                }
                """);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("active-provider-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "active-provider-demo");
        String providerName = "deep-test";
        int[] invocations = {0};
        CodeIntelligenceProvider provider = new CodeIntelligenceProvider() {
            @Override
            public String sourceProvider() {
                return providerName;
            }

            @Override
            public CodeIntelligenceSnapshot analyze(Path root) {
                invocations[0]++;
                return new CodeIntelligenceSnapshot(
                        providerName,
                        List.of(new IndexedSymbol(
                                "src/main/java/demo/App.java",
                                new CodeSymbol(
                                        SymbolKind.TYPE,
                                        "ResolvedDependency",
                                        "demo.ResolvedDependency",
                                        "ResolvedDependency",
                                        5,
                                        5,
                                        providerName))),
                        List.of(new IndexedRelation(
                                "src/main/java/demo/App.java",
                                new SymbolRelation(
                                        RelationKind.REFERENCES,
                                        "demo.App#run()",
                                        "demo.ResolvedDependency",
                                        1.0d,
                                        providerName))));
            }
        };

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths),
                List.of(),
                List.of(provider));

        IndexingReport baseline = service.index(project.id());
        assertEquals(0, invocations[0]);
        assertEquals(new IndexStatistics(1, 2, 0), baseline.statistics());

        IndexingReport deep = service.indexWithCodeIntelligence(project.id());
        assertEquals(1, invocations[0]);
        assertEquals(new IndexStatistics(1, 3, 1), deep.statistics());

        IndexingReport unchanged = service.index(project.id());
        assertEquals(1, invocations[0]);
        assertEquals(new IndexStatistics(1, 3, 1), unchanged.statistics());

        Files.writeString(sourceFile, """
                package demo;
                class App {
                    void run() {}
                    void stop() {}
                }
                """);
        IndexingReport changed = service.index(project.id());
        assertEquals(1, invocations[0]);
        assertEquals(new IndexStatistics(1, 3, 0), changed.statistics());
        assertTrue(indexRepository.findSymbols(project.id()).stream()
                .noneMatch(indexed -> indexed.symbol().sourceProvider().equals(providerName)));

        IndexingReport deepAgain = service.indexWithCodeIntelligence(project.id());
        assertEquals(2, invocations[0]);
        assertEquals(new IndexStatistics(1, 4, 1), deepAgain.statistics());
    }

    private static int luceneDocumentCount(NexusPaths paths, ProjectDescriptor project) throws Exception {
        try (Directory directory = FSDirectory.open(paths.projectLuceneIndex(project.id()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }
}
