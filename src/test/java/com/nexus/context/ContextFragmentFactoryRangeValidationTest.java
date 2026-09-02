package com.nexus.context;

import com.nexus.index.CodeSymbol;
import com.nexus.index.SymbolKind;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFragmentFactoryRangeValidationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsHistoricalRangeBeyondCanonicalFileInsteadOfSlicingPastEnd() throws Exception {
        Path source = temporaryDirectory.resolve("Three.java");
        Files.writeString(source, "one\ntwo\nthree");

        List<ContextFragment> fragments = factory().create(
                project(),
                "three",
                List.of(candidate(source, new CodeSymbol(
                        SymbolKind.METHOD,
                        "run",
                        "demo.Three#run()",
                        "run()",
                        2,
                        10,
                        "historical"))),
                1_000);

        assertTrue(fragments.isEmpty());
    }

    @Test
    void skipsSymbolAgainstEmptyFile() throws Exception {
        Path source = temporaryDirectory.resolve("Empty.java");
        Files.writeString(source, "");

        List<ContextFragment> fragments = factory().create(
                project(),
                "empty",
                List.of(candidate(source, symbol(1, 1))),
                1_000);

        assertTrue(fragments.isEmpty());
    }

    @Test
    void acceptsOneLineFileAndSymbolCoveringLastLine() throws Exception {
        Path oneLine = temporaryDirectory.resolve("One.java");
        Files.writeString(oneLine, "class One {}");
        List<ContextFragment> oneLineFragments = factory().create(
                project(),
                "One",
                List.of(candidate(oneLine, symbol(1, 1))),
                1_000);

        assertEquals(1, oneLineFragments.size());
        assertEquals(1, oneLineFragments.getFirst().startLine());
        assertEquals(1, oneLineFragments.getFirst().endLine());

        Path threeLines = temporaryDirectory.resolve("Last.java");
        Files.writeString(threeLines, "one\ntwo\nthree");
        List<ContextFragment> lastLineFragments = factory().create(
                project(),
                "three",
                List.of(candidate(threeLines, symbol(3, 3))),
                1_000);

        assertEquals(1, lastLineFragments.size());
        assertEquals(1, lastLineFragments.getFirst().startLine());
        assertEquals(3, lastLineFragments.getFirst().endLine());
    }

    @Test
    void materializesCrOnlyFilesWithTheSameLineModelUsedForRangeValidation() throws Exception {
        Path source = temporaryDirectory.resolve("Legacy.java");
        Files.writeString(source, "one\rtwo\rthree");

        List<ContextFragment> fragments = factory().create(
                project(),
                "three",
                List.of(candidate(source, symbol(2, 3))),
                1_000);

        assertEquals(1, fragments.size());
        assertEquals(1, fragments.getFirst().startLine());
        assertEquals(3, fragments.getFirst().endLine());
        assertTrue(fragments.getFirst().content().contains("two"));
        assertTrue(fragments.getFirst().content().contains("three"));
    }

    @Test
    void materializesMixedLineEndingsWithoutRangeDrift() throws Exception {
        Path source = temporaryDirectory.resolve("Mixed.java");
        Files.writeString(source, "one\r\ntwo\rthree\nfour");

        List<ContextFragment> fragments = factory().create(
                project(),
                "four",
                List.of(candidate(source, symbol(3, 4))),
                1_000);

        assertEquals(1, fragments.size());
        assertEquals(1, fragments.getFirst().startLine());
        assertEquals(4, fragments.getFirst().endLine());
        assertTrue(fragments.getFirst().content().contains("three"));
        assertTrue(fragments.getFirst().content().contains("four"));
    }

    private ContextFragmentFactory factory() {
        return new ContextFragmentFactory(text -> text.length());
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "fixture",
                temporaryDirectory,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }

    private static RankedCandidate candidate(Path source, CodeSymbol symbol) {
        SearchCandidate candidate = new SearchCandidate(
                source.toString() + "#" + symbol.qualifiedName(),
                CandidateType.SYMBOL,
                source,
                symbol,
                "",
                Map.of());
        return new RankedCandidate(candidate, 1.0d, Map.of(), List.of("fixture"));
    }

    private static CodeSymbol symbol(int startLine, int endLine) {
        return new CodeSymbol(
                SymbolKind.METHOD,
                "run",
                "demo.Type#run()",
                "run()",
                startLine,
                endLine);
    }
}
