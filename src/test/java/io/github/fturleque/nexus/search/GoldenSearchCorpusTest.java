package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.config.NexusPaths;
import io.github.fturleque.nexus.index.IndexRepository;
import io.github.fturleque.nexus.index.ProjectIndexingService;
import io.github.fturleque.nexus.index.java.JavaParserLanguageAnalyzer;
import io.github.fturleque.nexus.index.scan.ProjectScanner;
import io.github.fturleque.nexus.persistence.sqlite.SqliteDatabase;
import io.github.fturleque.nexus.persistence.sqlite.SqliteIndexRepository;
import io.github.fturleque.nexus.persistence.sqlite.SqliteProjectRepository;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRegistry;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.ranking.DeterministicContextRanker;
import io.github.fturleque.nexus.ranking.RankedCandidate;
import io.github.fturleque.nexus.ranking.graph.GraphCandidateEnricher;
import io.github.fturleque.nexus.search.evaluation.SearchQualityMetrics;
import io.github.fturleque.nexus.search.lucene.LuceneFileSearchStrategy;
import io.github.fturleque.nexus.search.lucene.LuceneSearchIndex;
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

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsRelevantFilesInTheTopResultsForGoldenQueries() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
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
                List.of(new JavaParserLanguageAnalyzer()),
                searchIndex).index(project.id());

        SearchService service = new SearchService(
                List.of(
                        new LuceneFileSearchStrategy(searchIndex),
                        new SymbolSearchStrategy(indexRepository)),
                new GraphCandidateEnricher(indexRepository),
                new DeterministicContextRanker());

        List<GoldenQuery> corpus = loadCorpus();
        double precisionSum = 0.0d;
        double recallSum = 0.0d;

        for (GoldenQuery goldenQuery : corpus) {
            List<RankedCandidate> results = service.search(project, goldenQuery.query(), 20, false);
            List<String> rankedPaths = distinctPaths(project, results);
            precisionSum += SearchQualityMetrics.precisionAtK(rankedPaths, goldenQuery.relevantPaths(), K);
            recallSum += SearchQualityMetrics.recallAtK(rankedPaths, goldenQuery.relevantPaths(), K);
        }

        double meanPrecision = precisionSum / corpus.size();
        double meanRecall = recallSum / corpus.size();
        System.out.printf(
                "NEXUS quality baseline: corpus=%d, mean precision@%d=%.4f, mean recall@%d=%.4f%n",
                corpus.size(), K, meanPrecision, K, meanRecall);
        assertTrue(meanPrecision >= 0.44d, "mean precision@3=" + meanPrecision);
        assertEquals(1.0d, meanRecall, 0.000001d, "mean recall@3");
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
