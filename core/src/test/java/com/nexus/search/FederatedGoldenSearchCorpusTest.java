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

import static org.junit.jupiter.api.Assertions.assertTrue;

class FederatedGoldenSearchCorpusTest {

    private static final int K = 3;
    private static final int MINIMUM_CORPUS_SIZE = 6;

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsRelevantProjectQualifiedFilesHighlyRankedAcrossProjects() throws Exception {
        Path projectARoot = Files.createDirectories(temporaryDirectory.resolve("project-a"));
        Path projectBRoot = Files.createDirectories(temporaryDirectory.resolve("project-b"));
        Path projectCRoot = Files.createDirectories(temporaryDirectory.resolve("project-c"));
        write(projectARoot, "src/main/java/demo/a/BillingService.java", """
                package demo.a;
                public class BillingService {
                    public void reconcileInvoice() {}
                    public void calculateInvoiceTax() {}
                }
                """);
        write(projectARoot, "src/main/java/demo/a/CustomerCreditPolicy.java", """
                package demo.a;
                public class CustomerCreditPolicy {
                    public boolean approveCustomerCredit() { return true; }
                }
                """);
        write(projectBRoot, "src/main/java/demo/b/InvoiceRepository.java", """
                package demo.b;
                public class InvoiceRepository {
                    public void reconcileInvoice() {}
                    public void persistInvoiceLedger() {}
                }
                """);
        write(projectBRoot, "src/main/java/demo/b/PaymentSettlementJob.java", """
                package demo.b;
                public class PaymentSettlementJob {
                    public void settleMerchantPayment() {}
                }
                """);
        write(projectCRoot, "src/main/java/demo/c/ShipmentPlanner.java", """
                package demo.c;
                public class ShipmentPlanner {
                    public void planDeliveryRoute() {}
                }
                """);
        write(projectCRoot, "src/main/java/demo/c/WarehouseAllocator.java", """
                package demo.c;
                public class WarehouseAllocator {
                    public void allocateWarehouseStock() {}
                }
                """);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectRegistry projectRegistry = new ProjectRegistry(projectRepository);
        ProjectDescriptor projectA = projectRegistry.register(projectARoot, "project-a");
        ProjectDescriptor projectB = projectRegistry.register(projectBRoot, "project-b");
        ProjectDescriptor projectC = projectRegistry.register(projectCRoot, "project-c");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                searchIndex);
        indexingService.index(projectA.id());
        indexingService.index(projectB.id());
        indexingService.index(projectC.id());

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
                                key(projectB, "src/main/java/demo/b/InvoiceRepository.java"))),
                new GoldenQuery(
                        "customer credit approval",
                        Set.of(key(projectA, "src/main/java/demo/a/CustomerCreditPolicy.java"))),
                new GoldenQuery(
                        "merchant payment settlement",
                        Set.of(key(projectB, "src/main/java/demo/b/PaymentSettlementJob.java"))),
                new GoldenQuery(
                        "delivery route shipment",
                        Set.of(key(projectC, "src/main/java/demo/c/ShipmentPlanner.java"))),
                new GoldenQuery(
                        "warehouse stock allocation",
                        Set.of(key(projectC, "src/main/java/demo/c/WarehouseAllocator.java"))),
                new GoldenQuery(
                        "invoice reconciliation billing repository",
                        Set.of(
                                key(projectA, "src/main/java/demo/a/BillingService.java"),
                                key(projectB, "src/main/java/demo/b/InvoiceRepository.java"))));
        assertTrue(corpus.size() >= MINIMUM_CORPUS_SIZE, "federated golden corpus must remain representative");

        double precisionSum = 0.0d;
        double recallSum = 0.0d;
        double reciprocalRankSum = 0.0d;
        double ndcgSum = 0.0d;
        for (GoldenQuery goldenQuery : corpus) {
            List<FederatedSearchHit> results = federatedSearchService.search(
                    List.of(projectA, projectB, projectC),
                    goldenQuery.query(),
                    20,
                    false);
            List<String> rankedKeys = distinctProjectQualifiedPaths(results);
            precisionSum += SearchQualityMetrics.precisionAtK(rankedKeys, goldenQuery.relevantKeys(), K);
            recallSum += SearchQualityMetrics.recallAtK(rankedKeys, goldenQuery.relevantKeys(), K);
            reciprocalRankSum += SearchQualityMetrics.reciprocalRank(rankedKeys, goldenQuery.relevantKeys());
            ndcgSum += SearchQualityMetrics.ndcgAtK(rankedKeys, goldenQuery.relevantKeys(), K);
        }

        double meanPrecision = precisionSum / corpus.size();
        double meanRecall = recallSum / corpus.size();
        double meanReciprocalRank = reciprocalRankSum / corpus.size();
        double meanNdcg = ndcgSum / corpus.size();
        System.out.printf(
                "NEXUS federated quality baseline: corpus=%d, mean precision@%d=%.4f, mean recall@%d=%.4f, MRR=%.4f, nDCG@%d=%.4f%n",
                corpus.size(), K, meanPrecision, K, meanRecall, meanReciprocalRank, K, meanNdcg);

        assertTrue(meanPrecision >= 0.30d, "federated mean precision@3=" + meanPrecision);
        assertTrue(meanRecall >= 0.90d, "federated mean recall@3=" + meanRecall);
        assertTrue(meanReciprocalRank >= 0.80d, "federated MRR=" + meanReciprocalRank);
        assertTrue(meanNdcg >= 0.80d, "federated mean nDCG@3=" + meanNdcg);
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
