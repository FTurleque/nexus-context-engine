package com.nexus.search;

import com.nexus.config.NexusPaths;
import com.nexus.index.IndexRepository;
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
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.evaluation.SearchQualityMetrics;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenSearchCorpusTest {

    private static final int K = 3;
    private static final int MINIMUM_CORPUS_SIZE = 10;

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsRelevantFilesHighlyRankedForRepresentativeQueries() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        writeFixtures(projectRoot);

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "demo");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);
        new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer(), new MarkdownLanguageAnalyzer()),
                searchIndex).index(project.id());

        SearchService service = new SearchService(
                List.of(
                        new LuceneFileSearchStrategy(searchIndex),
                        new SymbolSearchStrategy(indexRepository)),
                new GraphCandidateEnricher(indexRepository),
                new DeterministicContextRanker());

        List<GoldenQuery> corpus = loadCorpus();
        assertTrue(corpus.size() >= MINIMUM_CORPUS_SIZE, "golden corpus must not shrink below representative coverage");

        double precisionSum = 0.0d;
        double recallSum = 0.0d;
        double reciprocalRankSum = 0.0d;
        double ndcgSum = 0.0d;

        for (GoldenQuery goldenQuery : corpus) {
            List<RankedCandidate> results = service.search(project, goldenQuery.query(), 20, false);
            List<String> rankedPaths = distinctPaths(project, results);
            precisionSum += SearchQualityMetrics.precisionAtK(rankedPaths, goldenQuery.relevantPaths(), K);
            recallSum += SearchQualityMetrics.recallAtK(rankedPaths, goldenQuery.relevantPaths(), K);
            reciprocalRankSum += SearchQualityMetrics.reciprocalRank(rankedPaths, goldenQuery.relevantPaths());
            ndcgSum += SearchQualityMetrics.ndcgAtK(rankedPaths, goldenQuery.relevantPaths(), K);
        }

        double meanPrecision = precisionSum / corpus.size();
        double meanRecall = recallSum / corpus.size();
        double meanReciprocalRank = reciprocalRankSum / corpus.size();
        double meanNdcg = ndcgSum / corpus.size();
        System.out.printf(
                "NEXUS quality baseline: corpus=%d, mean precision@%d=%.4f, mean recall@%d=%.4f, MRR=%.4f, nDCG@%d=%.4f%n",
                corpus.size(), K, meanPrecision, K, meanRecall, meanReciprocalRank, K, meanNdcg);

        assertTrue(meanPrecision >= 0.30d, "mean precision@3=" + meanPrecision);
        assertTrue(meanRecall >= 0.90d, "mean recall@3=" + meanRecall);
        assertTrue(meanReciprocalRank >= 0.80d, "MRR=" + meanReciprocalRank);
        assertTrue(meanNdcg >= 0.80d, "mean nDCG@3=" + meanNdcg);
    }

    private static void writeFixtures(Path projectRoot) throws Exception {
        write(projectRoot, "src/main/java/demo/pdf/PdfUploadService.java", """
                package demo.pdf;
                import demo.storage.DocumentRepository;
                public class PdfUploadService {
                    private final DocumentRepository repository = new DocumentRepository();
                    public void uploadPdf(byte[] content) {
                        repository.saveDocument(content);
                    }
                }
                """);
        write(projectRoot, "src/main/java/demo/storage/DocumentRepository.java", """
                package demo.storage;
                public class DocumentRepository {
                    public void saveDocument(byte[] content) {}
                }
                """);
        write(projectRoot, "src/main/java/demo/web/UnrelatedController.java", """
                package demo.web;
                public class UnrelatedController {
                    public void health() {}
                }
                """);
        write(projectRoot, "src/main/java/demo/security/JwtTokenValidator.java", """
                package demo.security;
                public class JwtTokenValidator {
                    public boolean validateBearerToken(String token) {
                        return token != null && !token.isBlank(); // signature and expiration validation fixture
                    }
                }
                """);
        write(projectRoot, "src/main/java/demo/order/OrderCheckoutService.java", """
                package demo.order;
                import demo.payment.PaymentGatewayClient;
                public class OrderCheckoutService {
                    private final PaymentGatewayClient paymentGateway = new PaymentGatewayClient();
                    public void checkoutOrder(String orderId) {
                        paymentGateway.capturePayment(orderId);
                    }
                }
                """);
        write(projectRoot, "src/main/java/demo/payment/PaymentGatewayClient.java", """
                package demo.payment;
                public class PaymentGatewayClient {
                    public void capturePayment(String orderId) {
                        // retry timeout payment gateway fixture
                    }
                }
                """);
        write(projectRoot, "src/main/java/demo/inventory/InventoryReservationService.java", """
                package demo.inventory;
                public class InventoryReservationService {
                    public void reserveStock(String sku) {
                        // warehouse stock reservation fixture
                    }
                }
                """);
        write(projectRoot, "src/main/java/demo/profile/UserProfileController.java", """
                package demo.profile;
                public class UserProfileController {
                    public void updateProfileAvatar(String userId, String avatarUrl) {}
                }
                """);
        write(projectRoot, "src/main/java/demo/audit/AuditEventRepository.java", """
                package demo.audit;
                public class AuditEventRepository {
                    public void persistSecurityAuditEvent(String event) {}
                }
                """);
        write(projectRoot, "src/main/java/demo/cache/CacheEvictionJob.java", """
                package demo.cache;
                public class CacheEvictionJob {
                    public void evictExpiredCacheEntries() {}
                }
                """);
        write(projectRoot, "docs/security.md", """
                # Remote security

                Remote deployments require bearer token rotation and TLS termination.
                Never expose a remote endpoint without an explicit trust boundary.
                """);
    }

    private static List<String> distinctPaths(ProjectDescriptor project, List<RankedCandidate> results) {
        Set<String> paths = new LinkedHashSet<>();
        for (RankedCandidate result : results) {
            paths.add(project.rootPath().relativize(result.candidate().path()).toString().replace('\\', '/'));
        }
        return List.copyOf(paths);
    }

    private static List<GoldenQuery> loadCorpus() throws Exception {
        InputStream stream = GoldenSearchCorpusTest.class.getResourceAsStream("/corpus/search-golden-queries.tsv");
        if (stream == null) {
            throw new IllegalStateException("Corpus golden introuvable");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(GoldenSearchCorpusTest::parseGoldenQuery)
                    .toList();
        }
    }

    private static GoldenQuery parseGoldenQuery(String line) {
        String[] columns = line.split("\\t", 2);
        if (columns.length != 2) {
            throw new IllegalArgumentException("Ligne de corpus invalide : " + line);
        }
        return new GoldenQuery(
                columns[0],
                Set.copyOf(Arrays.asList(columns[1].split(";"))));
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private record GoldenQuery(String query, Set<String> relevantPaths) {
    }
}
