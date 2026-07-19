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
import com.nexus.ranking.RankedCandidate;
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void combinesLexicalSymbolAndGraphSignalsDeterministically() throws Exception {
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
                    public void saveDocument(byte[] content) {
                    }
                }
                """);
        write(projectRoot, "src/main/java/demo/web/UnrelatedController.java", """
                package demo.web;

                public class UnrelatedController {
                    public void health() {
                    }
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

        List<RankedCandidate> first = service.search(project, "upload PDF", 10, true);
        List<RankedCandidate> second = service.search(project, "upload PDF", 10, true);

        assertFalse(first.isEmpty());
        assertTrue(first.getFirst().candidate().path().endsWith("PdfUploadService.java"));
        assertEquals(
                first.stream().map(candidate -> candidate.candidate().id()).toList(),
                second.stream().map(candidate -> candidate.candidate().id()).toList());
        assertEquals(
                first.stream().map(RankedCandidate::score).toList(),
                second.stream().map(RankedCandidate::score).toList());

        RankedCandidate repositoryCandidate = first.stream()
                .filter(candidate -> candidate.candidate().path().endsWith("DocumentRepository.java"))
                .findFirst()
                .orElseThrow();
        assertTrue(repositoryCandidate.candidate().signals().getOrDefault(SearchSignals.GRAPH, 0.0d) > 0.0d);
        assertTrue(repositoryCandidate.reasons().stream().anyMatch(reason -> reason.contains("graphe")));

        List<RankedCandidate> fuzzy = service.search(project, "DocumntRepository", 5, true);
        assertFalse(fuzzy.isEmpty());
        assertTrue(fuzzy.getFirst().candidate().symbol() != null);
        assertEquals("DocumentRepository", fuzzy.getFirst().candidate().symbol().name());
        assertTrue(fuzzy.getFirst().candidate().signals().getOrDefault(SearchSignals.SYMBOL_FUZZY, 0.0d) >= 0.62d);
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
