package com.nexus.context;

import com.nexus.config.NexusPaths;
import com.nexus.context.source.skill.LocalAgentSkillsProvider;
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
import com.nexus.ranking.graph.GraphCandidateEnricher;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchService;
import com.nexus.search.SymbolSearchStrategy;
import com.nexus.search.lucene.LuceneFileSearchStrategy;
import com.nexus.search.lucene.LuceneSearchIndex;
import com.nexus.token.HeuristicTokenEstimator;
import com.nexus.token.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSkillsIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void activatesOnlyRelevantSkillAndNeverLoadsOrExecutesItsResourcesAutomatically() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        write(projectRoot, "src/main/java/app/PdfService.java", """
                package app;
                public class PdfService {
                    public String extractForm(byte[] pdf) { return "ok"; }
                }
                """);

        write(projectRoot, ".agents/skills/pdf-processing/SKILL.md", """
                ---
                name: pdf-processing
                description: Extract PDF text, fill forms and process PDF documents. Use for PDF extraction tasks.
                metadata:
                  version: "1.0"
                ---
                # PDF processing workflow
                PDF_SKILL_BODY
                1. Inspect the PDF input.
                2. Extract the requested form data.
                3. Validate the result before returning it.
                """);
        write(projectRoot, ".agents/skills/pdf-processing/references/REFERENCE.md", "REFERENCE_NOT_AUTO_LOADED");
        write(projectRoot, ".agents/skills/pdf-processing/scripts/should-not-run.ps1", "Set-Content executed.txt SHOULD_NOT_EXECUTE");

        write(projectRoot, ".claude/skills/pdf-processing/SKILL.md", """
                ---
                name: pdf-processing
                description: Extract PDF text, fill forms and process PDF documents. Use for PDF extraction tasks.
                ---
                DUPLICATE_SKILL_BODY
                """);

        write(projectRoot, ".github/skills/database-migration/SKILL.md", """
                ---
                name: database-migration
                description: Plan relational database schema migrations and SQL deployment rollouts.
                ---
                IRRELEVANT_SKILL_BODY
                """);

        Fixture fixture = fixture(projectRoot);
        fixture.indexingService().index(fixture.project().id());

        ContextBundle bundle = fixture.contextBuilder().build(new ContextRequest(
                fixture.project().id(),
                "extract PDF forms with PdfService",
                4_000,
                Set.of(),
                Map.of(),
                true));

        assertTrue(bundle.estimatedTokens() <= 4_000);
        assertTrue(bundle.items().stream().anyMatch(item -> item.type() == CandidateType.SKILL));
        assertTrue(bundle.items().stream()
                .filter(item -> item.type() == CandidateType.SKILL)
                .noneMatch(ContextItem::truncated));

        String selectedContent = bundle.items().stream()
                .map(ContextItem::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(selectedContent.contains("PDF_SKILL_BODY"));
        assertFalse(selectedContent.contains("IRRELEVANT_SKILL_BODY"));
        assertFalse(selectedContent.contains("DUPLICATE_SKILL_BODY"));
        assertFalse(selectedContent.contains("REFERENCE_NOT_AUTO_LOADED"));
        assertFalse(selectedContent.contains("SHOULD_NOT_EXECUTE"));
        assertFalse(Files.exists(projectRoot.resolve("executed.txt")));

        assertTrue(((Number) bundle.metadata().get("skillsDiscovered")).intValue() >= 2);
        assertTrue(bundle.metadata().get("skillsMatched").toString().contains("pdf-processing"));
        assertFalse(bundle.metadata().get("skillsMatched").toString().contains("database-migration"));
        assertTrue(((Number) bundle.metadata().get("skillResourcesDiscovered")).intValue() >= 2);
        assertTrue(((Number) bundle.metadata().get("skillSelectedItems")).intValue() >= 1);
        assertTrue(Boolean.FALSE.equals(bundle.metadata().get("skillsExecuted")));
        assertFalse(bundle.metadata().get("skillsDeduplicated").toString().equals("[]"));
    }

    private Fixture fixture(Path projectRoot) throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "skills-context");
        SearchIndex searchIndex = new LuceneSearchIndex(paths);
        ProjectIndexingService indexingService = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(
                        new JavaParserLanguageAnalyzer(),
                        new MarkdownLanguageAnalyzer()),
                searchIndex);
        SearchService searchService = new SearchService(
                List.of(
                        new LuceneFileSearchStrategy(searchIndex),
                        new SymbolSearchStrategy(indexRepository)),
                new GraphCandidateEnricher(indexRepository),
                new DeterministicContextRanker());
        TokenEstimator tokenEstimator = new HeuristicTokenEstimator();
        ContextBuilder contextBuilder = new DefaultContextBuilder(
                projectRepository,
                searchService,
                new ContextFragmentFactory(tokenEstimator),
                new FragmentMerger(),
                new BudgetedContextSelector(tokenEstimator),
                tokenEstimator,
                List.of(),
                List.of(new LocalAgentSkillsProvider()));
        return new Fixture(project, indexingService, contextBuilder);
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private record Fixture(
            ProjectDescriptor project,
            ProjectIndexingService indexingService,
            ContextBuilder contextBuilder) {
    }
}
