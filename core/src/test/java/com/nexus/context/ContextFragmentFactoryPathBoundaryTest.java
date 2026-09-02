package com.nexus.context;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFragmentFactoryPathBoundaryTest {

    private static final String OUTSIDE_SECRET = "NXA07-OUTSIDE-SECRET-7f45c9";

    @TempDir
    Path temporaryDirectory;

    private Path projectRoot;
    private Path outsideFile;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = Files.createDirectory(temporaryDirectory.resolve("project"));
        outsideFile = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outsideFile, OUTSIDE_SECRET);
    }

    @Test
    void acceptsNormalRelativeFileInsideProject() throws Exception {
        Files.writeString(projectRoot.resolve("inside.txt"), "safe content");

        ContextFragmentFactory.MaterializationResult result = materialize(Path.of("inside.txt"));

        assertEquals(1, result.fragments().size());
        assertEquals(Path.of("inside.txt"), result.fragments().getFirst().path());
        assertEquals("safe content", result.fragments().getFirst().content());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void rejectsParentTraversalWithoutLeakingOutsideContent() throws Exception {
        ContextFragmentFactory.MaterializationResult result = materialize(Path.of("..", "outside.txt"));

        assertRejectedWithoutSecret(result);
    }

    @Test
    void rejectsAbsolutePathOutsideProjectWithoutLeakingOutsideContent() throws Exception {
        ContextFragmentFactory.MaterializationResult result = materialize(outsideFile.toAbsolutePath());

        assertRejectedWithoutSecret(result);
    }

    @Test
    void rejectsInternalSymlinkComponentLeadingOutsideProject() throws Exception {
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside-dir"));
        Files.writeString(outsideDirectory.resolve("secret.txt"), OUTSIDE_SECRET);
        Path link = projectRoot.resolve("linked-dir");
        assumeSymlink(link, outsideDirectory);

        ContextFragmentFactory.MaterializationResult result = materialize(link.resolve("secret.txt"));

        assertRejectedWithoutSecret(result);
    }

    @Test
    void rejectsFileReplacedBySymlinkAfterCandidateSelection() throws Exception {
        Path selected = projectRoot.resolve("selected.txt");
        Files.writeString(selected, "original safe content");
        RankedCandidate candidate = candidate(selected);
        Files.delete(selected);
        assumeSymlink(selected, outsideFile);

        ContextFragmentFactory.MaterializationResult result = factory().materialize(
                project(),
                "secret",
                List.of(candidate),
                1_000);

        assertRejectedWithoutSecret(result);
    }

    @Test
    void rejectsFinalSymlinkWithoutLeakingOutsideContent() throws Exception {
        Path link = projectRoot.resolve("final-link.txt");
        assumeSymlink(link, outsideFile);

        ContextFragmentFactory.MaterializationResult result = materialize(link);

        assertRejectedWithoutSecret(result);
    }

    @Test
    void missingFileAfterCandidateSelectionIsExcludedWithDiagnostic() throws Exception {
        Path selected = projectRoot.resolve("deleted.txt");
        Files.writeString(selected, "temporary safe content");
        RankedCandidate candidate = candidate(selected);
        Files.delete(selected);

        ContextFragmentFactory.MaterializationResult result = factory().materialize(
                project(),
                "temporary",
                List.of(candidate),
                1_000);

        assertTrue(result.fragments().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().getFirst().contains("deleted.txt"));
        assertFalse(bundleContent(result).contains(OUTSIDE_SECRET));
    }

    @Test
    void acceptsCanonicalInternalPath() throws Exception {
        Path nested = Files.createDirectories(projectRoot.resolve("src"));
        Path source = nested.resolve("Valid.java");
        Files.writeString(source, "class Valid {}");

        ContextFragmentFactory.MaterializationResult result = materialize(source.toRealPath());

        assertEquals(1, result.fragments().size());
        assertEquals(Path.of("src", "Valid.java"), result.fragments().getFirst().path());
        assertEquals("class Valid {}", result.fragments().getFirst().content());
        assertTrue(result.diagnostics().isEmpty());
    }

    private ContextFragmentFactory.MaterializationResult materialize(Path path) throws Exception {
        return factory().materialize(project(), "content", List.of(candidate(path)), 1_000);
    }

    private ContextFragmentFactory factory() {
        return new ContextFragmentFactory(text -> Math.max(1, text.length()));
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "boundary-fixture",
                projectRoot,
                ProjectSourceType.LOCAL,
                Set.of("java", "txt"),
                Set.of(),
                null,
                IndexStatus.READY);
    }

    private static RankedCandidate candidate(Path path) {
        SearchCandidate candidate = new SearchCandidate(
                "fixture:" + path,
                CandidateType.FILE,
                path,
                null,
                "fixture",
                Map.of());
        return new RankedCandidate(candidate, 1.0d, Map.of(), List.of("fixture"));
    }

    private static void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are not supported in this test environment: " + exception);
        }
    }

    private static void assertRejectedWithoutSecret(ContextFragmentFactory.MaterializationResult result) {
        assertTrue(result.fragments().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        assertFalse(bundleContent(result).contains(OUTSIDE_SECRET));
    }

    private static String bundleContent(ContextFragmentFactory.MaterializationResult result) {
        BudgetedContextSelector selector = new BudgetedContextSelector(text -> Math.max(1, text.length()));
        ContextSelectionResult selection = result.fragments().isEmpty()
                ? new ContextSelectionResult(List.of(), List.of(), 0, 0, 0)
                : selector.select(result.fragments(), 1_000, true);
        List<String> excluded = new ArrayList<>(selection.excluded());
        excluded.addAll(result.diagnostics());
        ContextBundle bundle = new ContextBundle(
                selection.items(),
                1_000,
                selection.selectedEstimatedTokens(),
                excluded,
                Map.of());
        return bundle.items().stream()
                .map(ContextItem::content)
                .collect(Collectors.joining("\n"));
    }
}
