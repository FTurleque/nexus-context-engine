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
import com.nexus.search.evaluation.SearchQualityMetrics;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedGoldenSearchCorpusTest {

    private static final int K = 3;

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsRelevantProjectQualifiedFilesInFederatedTopResults() throws Exception {
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

        List<GoldenQuery> corpus = List.of(
                new GoldenQuery(
                        "BillingService",
                        Set.of(key(projectA, "src/main/java/demo/a/BillingService.java"))),
                new GoldenQuery(
                        "InvoiceRepository",
                        Set.of(key(projectB, "src/main/java/demo/b/InvoiceRepository.java"))),
                new GoldenQuery(
                        "reconcileInvoice",
                        Set.of(
                                key(projectA, "src/main/java/demo/a/BillingService.java"),
                                key(projectB, "src/main/java/demo/b/InvoiceRepository.java"))));

        double precisionSum = 0.0d;
        double recallSum = 0.0d;
        for (GoldenQuery goldenQuery : corpus) {
            List<FederatedSearchHit> results = federatedSearchService.search(
                    List.of(projectA, projectB),
                    goldenQuery.query(),
                    20,
                    false);
            List<String> rankedKeys = distinctProjectQualifiedPaths(results);
            precisionSum += SearchQualityMetrics.precisionAtK(rankedKeys, goldenQuery.relevantKeys(), K);
            recallSum += SearchQualityMetrics.recallAtK(rankedKeys, goldenQuery.relevantKeys(), K);
        }

        double meanPrecision = precisionSum / corpus.size();
        double meanRecall = recallSum / corpus.size();
        System.out.printf(
                "NEXUS federated quality baseline: corpus=%d, mean precision@%d=%.4f, mean recall@%d=%.4f%n",
                corpus.size(), K, meanPrecision, K, meanRecall);
        assertTrue(meanPrecision >= 0.44d, "federated mean precision@3=" + meanPrecision);
        assertEquals(1.0d, meanRecall, 0.000001d, "federated mean recall@3");
    }

    private static List<String> distinctProjectQualifiedPaths(List<FederatedSearchHit> results) {
        Set<String> paths = new LinkedHashSet<>();
        for (FederatedSearchHit result : results) {
            String relativePath = result.project().rootPath()
                    .relativize(result.rankedCandidate().candidate().path())
                    .toString()
                    .replace('\\', '/');
            paths.add(result.project().id() + ":" + relativePath);
        }
        return List.copyOf(paths);
    }

    private static String key(ProjectDescriptor project, String relativePath) {
        return project.id() + ":" + relativePath;
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private record GoldenQuery(String query, Set<String> relevantKeys) {
    }
}
