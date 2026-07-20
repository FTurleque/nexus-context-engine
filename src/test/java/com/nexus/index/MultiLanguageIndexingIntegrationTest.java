package com.nexus.index;

import com.nexus.config.NexusPaths;
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
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLanguageIndexingIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesAndSearchesAdditionalLanguagesWithoutStructuralAnalyzers() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        write(projectRoot, "src/main/python/invoice_pipeline.py", """
                def reconcile_invoice_payments(invoices):
                    return [invoice for invoice in invoices if invoice.is_paid]
                """);
        write(projectRoot, "src/frontend/invoice-dashboard.ts", """
                export function renderInvoiceDashboard(invoices: Invoice[]): string {
                    return invoices.map(invoice => invoice.id).join(',');
                }
                """);
        write(projectRoot, "src/main/kotlin/demo/InvoiceService.kt", """
                package demo
                class InvoiceService {
                    fun loadInvoices(): List<String> = emptyList()
                }
                """);
        write(projectRoot, "db/invoice_report.sql", """
                select invoice_id, paid_at
                from invoice_payment
                where paid_at is not null;
                """);
        write(projectRoot, "src/ignored/legacy.rb", "puts 'not supported'");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "multi-language");
        LuceneSearchIndex searchIndex = new LuceneSearchIndex(paths);

        IndexingReport report = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(),
                searchIndex).index(project.id());

        ProjectDescriptor indexedProject = projectRepository.findById(project.id()).orElseThrow();
        assertEquals(Set.of("python", "typescript", "kotlin", "sql"), indexedProject.languages());
        assertEquals(4, report.statistics().files());
        assertEquals(0, report.statistics().symbols());
        assertEquals(0, report.statistics().relations());

        SearchService searchService = new SearchService(
                List.of(new LuceneFileSearchStrategy(searchIndex)),
                List.of(),
                new DeterministicContextRanker());

        List<RankedCandidate> pythonResults = searchService.search(
                indexedProject,
                "reconcile invoice payments",
                5,
                true);
        assertFalse(pythonResults.isEmpty());
        assertTrue(pythonResults.getFirst().candidate().path().endsWith("invoice_pipeline.py"));
        assertTrue(pythonResults.getFirst().reasons().stream().anyMatch(reason -> reason.contains("lexicale")));

        List<RankedCandidate> typeScriptResults = searchService.search(
                indexedProject,
                "render invoice dashboard",
                5,
                true);
        assertFalse(typeScriptResults.isEmpty());
        assertTrue(typeScriptResults.getFirst().candidate().path().endsWith("invoice-dashboard.ts"));

        List<RankedCandidate> sqlResults = searchService.search(
                indexedProject,
                "invoice payment paid at",
                5,
                true);
        assertFalse(sqlResults.isEmpty());
        assertTrue(sqlResults.getFirst().candidate().path().endsWith("invoice_report.sql"));
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
