package com.nexus.context;

import com.nexus.config.NexusPaths;
import com.nexus.context.source.instruction.AgentsMdInstructionProvider;
import com.nexus.context.source.instruction.ClaudeInstructionProvider;
import com.nexus.context.source.instruction.CopilotInstructionProvider;
import com.nexus.context.source.instruction.GeminiInstructionProvider;
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

class NativeContextSourcesIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void combinesApplicableNativeInstructionsCodeAndDocumentationWithoutInjectingOperationalConfig()
            throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        write(projectRoot, "src/main/java/app/service/OrderService.java", """
                package app.service;
                public class OrderService {
                    public void processOrder() {}
                }
                """);
        write(projectRoot, "src/main/java/app/web/WebController.java", """
                package app.web;
                public class WebController {
                    public void status() {}
                }
                """);
        write(projectRoot, "docs/order-processing.md", """
                # Order processing architecture
                OrderService coordinates the order processing workflow and transaction boundary.
                """);
        write(projectRoot, "docs/architecture-guide.md", """
                REFERENCED_ARCHITECTURE_RULE: preserve application boundaries.
                """);

        write(projectRoot, "AGENTS.md", """
                GLOBAL_AGENT_RULE: use Java 21 and run tests.
                See @docs/architecture-guide.md
                """);
        write(projectRoot, "src/main/java/app/service/AGENTS.md", """
                SERVICE_AGENT_RULE: service changes must preserve transaction boundaries.
                """);
        write(projectRoot, ".github/copilot-instructions.md", """
                COPILOT_GLOBAL_RULE: prefer minimal targeted changes.
                """);
        write(projectRoot, ".github/instructions/service.instructions.md", """
                ---
                applyTo: "src/main/java/app/service/**"
                ---
                COPILOT_SERVICE_RULE: validate service behavior with tests.
                """);
        write(projectRoot, ".github/instructions/web.instructions.md", """
                ---
                applyTo: "src/main/java/app/web/**"
                ---
                COPILOT_WEB_ONLY: this must not apply to OrderService context.
                """);
        write(projectRoot, ".claude/CLAUDE.md", """
                CLAUDE_REPO_RULE: explain architectural tradeoffs explicitly.
                """);

        write(projectRoot, ".claude/settings.json", "{\"marker\":\"SETTINGS_ONLY\"}");
        write(projectRoot, ".github/agents/docs.agent.md", "AGENT_PROFILE_ONLY");
        write(projectRoot, ".github/skills/demo/SKILL.md", "SKILL_ONLY");

        Fixture fixture = fixture(projectRoot);
        fixture.indexingService().index(fixture.project().id());

        ContextBundle bundle = fixture.contextBuilder().build(new ContextRequest(
                fixture.project().id(),
                "OrderService order processing architecture",
                4_000,
                Set.of(),
                Map.of(),
                true));

        assertTrue(bundle.estimatedTokens() <= 4_000);
        assertTrue(bundle.items().stream().anyMatch(item -> item.type() == CandidateType.INSTRUCTION));
        assertTrue(bundle.items().stream().anyMatch(item -> item.type() == CandidateType.DOCUMENTATION));
        assertTrue(bundle.items().stream().anyMatch(item ->
                item.type() == CandidateType.FILE || item.type() == CandidateType.SYMBOL));

        String selectedContent = bundle.items().stream()
                .map(ContextItem::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(selectedContent.contains("GLOBAL_AGENT_RULE"));
        assertTrue(selectedContent.contains("SERVICE_AGENT_RULE"));
        assertTrue(selectedContent.contains("COPILOT_GLOBAL_RULE"));
        assertTrue(selectedContent.contains("COPILOT_SERVICE_RULE"));
        assertTrue(selectedContent.contains("CLAUDE_REPO_RULE"));
        assertTrue(selectedContent.contains("REFERENCED_ARCHITECTURE_RULE"));
        assertFalse(selectedContent.contains("COPILOT_WEB_ONLY"));
        assertFalse(selectedContent.contains("SETTINGS_ONLY"));
        assertFalse(selectedContent.contains("AGENT_PROFILE_ONLY"));
        assertFalse(selectedContent.contains("SKILL_ONLY"));

        assertTrue(((Number) bundle.metadata().get("nativeSourcesDiscovered")).intValue() >= 5);
        assertTrue(((Number) bundle.metadata().get("documentationCandidates")).longValue() >= 1L);
        Object customizations = bundle.metadata().get("nativeCustomizationsDetected");
        assertTrue(customizations instanceof Map<?, ?>);
        String customizationText = customizations.toString();
        assertTrue(customizationText.contains(".claude/settings.json"));
        assertTrue(customizationText.contains(".github/agents/docs.agent.md"));
        assertTrue(customizationText.contains(".github/skills/demo/SKILL.md"));
    }

    private Fixture fixture(Path projectRoot) throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "native-context");
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
                List.of(
                        new AgentsMdInstructionProvider(),
                        new CopilotInstructionProvider(),
                        new ClaudeInstructionProvider(),
                        new GeminiInstructionProvider()));
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
