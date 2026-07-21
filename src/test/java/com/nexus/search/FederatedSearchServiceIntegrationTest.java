package com.nexus.search;

import com.nexus.config.NexusPaths;
import com.nexus.index.IndexRepository;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.DeterministicContextRanker;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedSearchServiceIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void searchesExplicitProjectsPreservesProvenanceAndDiversifiesPaths() throws Exception {
        Path projectARoot = Files.createDirectories(temporaryDirectory.resolve("project-a"));
        Path projectBRoot = Files.createDirectories(temporaryDirectory.resolve("project-b"));
        write(projectARoot, "src/main/java/demo/a/BillingService.java", """
                package demo.a;
                public class BillingService {
                    public void reconcileInvoice() {}
                }
                """);
        write(projectBRoot, "src/main/java/demo/b/InvoiceRepository.java", """
                package demo.b;
                public class InvoiceRepository {
                    public void reconcileInvoice() {}
                }
                """);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry projectRegistry = new ProjectRegistry(projectRepository);
        ProjectDescriptor projectA = projectRegistry.register(projectARoot, "project-a");
        ProjectDescriptor projectB = projectRegistry.register(projectBRoot, "project-b");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                searchIndex);
        indexingService.index(projectA.id());
        indexingService.index(projectB.id());

        SearchService searchService = new SearchService(
                List.of(
                        new LuceneFileSearchStrategy(searchIndex),
                        new SymbolSearchStrategy(indexRepository)),
                new GraphCandidateEnricher(indexRepository),
                new DeterministicContextRanker());
        FederatedSearchService federatedSearchService = new FederatedSearchService(searchService);

        List<FederatedSearchHit> results = federatedSearchService.search(
                List.of(projectA, projectB),
                "reconcileInvoice",
                10,
                true);

        assertFalse(results.isEmpty());
        Set<java.util.UUID> projectIds = results.stream()
                .map(hit -> hit.project().id())
                .collect(Collectors.toSet());
        assertEquals(Set.of(projectA.id(), projectB.id()), projectIds);
        assertTrue(results.stream().allMatch(hit -> hit.rankedCandidate().candidate().path()
                .startsWith(hit.project().rootPath())));

        Set<String> projectPaths = results.stream()
                .map(hit -> hit.project().id() + ":" + hit.rankedCandidate().candidate().path().toAbsolutePath().normalize())
                .collect(Collectors.toSet());
        assertEquals(results.size(), projectPaths.size());

        List<FederatedSearchHit> projectAOnly = federatedSearchService.search(
                List.of(projectA),
                "reconcileInvoice",
                10,
                false);
        assertFalse(projectAOnly.isEmpty());
        assertTrue(projectAOnly.stream().allMatch(hit -> hit.project().id().equals(projectA.id())));

        List<FederatedSearchHit> deduplicatedScope = federatedSearchService.search(
                List.of(projectA, projectA),
                "reconcileInvoice",
                10,
                false);
        assertEquals(projectAOnly, deduplicatedScope);
    }

    @Test
    void rejectsAnEmptyProjectScope() {
        SearchService searchService = new SearchService(
                List.of(),
                List.of(),
                new DeterministicContextRanker());
        FederatedSearchService federatedSearchService = new FederatedSearchService(searchService);

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> federatedSearchService.search(List.of(), "query", 10, false));

        assertEquals("projects must not be empty", exception.getMessage());
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
