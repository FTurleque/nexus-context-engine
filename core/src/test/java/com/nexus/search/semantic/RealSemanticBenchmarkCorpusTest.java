package com.nexus.search.semantic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealSemanticBenchmarkCorpusTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearCorpusProperty() {
        System.clearProperty(RealSemanticBenchmarkCorpus.CORPUS_PROPERTY);
    }

    @Test
    void corpusDefaultsToFullAndAcceptsOnlyFullOrPr() {
        System.clearProperty(RealSemanticBenchmarkCorpus.CORPUS_PROPERTY);
        assertEquals(RealSemanticBenchmarkCorpus.Kind.FULL, RealSemanticBenchmarkCorpus.Kind.fromSystemProperties());

        System.setProperty(RealSemanticBenchmarkCorpus.CORPUS_PROPERTY, "pr");
        assertEquals(RealSemanticBenchmarkCorpus.Kind.PR, RealSemanticBenchmarkCorpus.Kind.fromSystemProperties());

        System.setProperty(RealSemanticBenchmarkCorpus.CORPUS_PROPERTY, "partial");
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                RealSemanticBenchmarkCorpus.Kind::fromSystemProperties);
        assertTrue(rejected.getMessage().contains("partial"));
    }

    @Test
    void prCorpusKeepsDeclaredSlicesOnly() throws IOException {
        Path root = fixtureWithEveryRelevantPath();
        write(root, "docs/architecture/adr/BE-0099-distracteur.md");
        write(root, "docs/architecture/adr/BE-0099-distracteur.rules.ts");
        write(root, "docs/architecture/adr/archive/BE-0001-ancien.md");
        write(root, "docs/architecture/adr/GEN-0099-hors-corpus.md");
        write(root, "docs/project/history/hors-corpus.md");
        write(root, "core/src/test/java/com/nexus/search/HorsCorpusTest.java");

        Set<String> files = RealSemanticBenchmarkCorpus.prCorpusFiles(root);

        assertTrue(files.contains("docs/architecture/adr/BE-0099-distracteur.md"));
        assertFalse(files.contains("docs/architecture/adr/BE-0099-distracteur.rules.ts"));
        assertFalse(files.contains("docs/architecture/adr/archive/BE-0001-ancien.md"));
        assertFalse(files.contains("docs/architecture/adr/GEN-0099-hors-corpus.md"));
        assertFalse(files.contains("docs/project/history/hors-corpus.md"));
        assertFalse(files.contains("core/src/test/java/com/nexus/search/HorsCorpusTest.java"));
        assertEquals(List.of(), RealSemanticBenchmarkCorpus.missingRelevantPaths(files));
    }

    @Test
    void materializedPrCorpusCopiesSelectedFiles() throws IOException {
        Path root = fixtureWithEveryRelevantPath();
        write(root, "docs/project/history/hors-corpus.md");

        Path corpus = RealSemanticBenchmarkCorpus.materializePrCorpus(root, temporaryDirectory.resolve("corpus"));

        assertTrue(Files.isRegularFile(corpus.resolve("docs/developer/mcp.md")));
        assertEquals("contenu docs/developer/mcp.md", Files.readString(corpus.resolve("docs/developer/mcp.md")));
        assertFalse(Files.exists(corpus.resolve("docs/project")));
    }

    @Test
    void materializationFailsWhenARelevantPathLeavesThePrCorpus() throws IOException {
        Path root = fixtureWithEveryRelevantPath();
        Files.delete(root.resolve("docs/developer/mcp.md"));

        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> RealSemanticBenchmarkCorpus.materializePrCorpus(root, temporaryDirectory.resolve("corpus")));
        assertTrue(rejected.getMessage().contains("docs/developer/mcp.md"));
    }

    @Test
    void emptySliceIsRejected() throws IOException {
        Path root = fixtureWithEveryRelevantPath();
        List<Path> sliceFiles;
        try (var paths = Files.walk(root.resolve("adapters/mcp-java/src/main"))) {
            sliceFiles = paths.filter(Files::isRegularFile).toList();
        }
        for (Path file : sliceFiles) {
            Files.delete(file);
        }

        IOException rejected = assertThrows(IOException.class, () -> RealSemanticBenchmarkCorpus.prCorpusFiles(root));
        assertTrue(rejected.getMessage().contains("adapters/mcp-java/src/main"));
    }

    // Lit le dépôt NEXUS lui-même : garde contre la dérive entre les jugements de
    // pertinence des requêtes et les tranches du corpus pr, que seul le benchmark réel exerce sinon.
    @Test
    void repositoryPrCorpusContainsEveryRelevantPath() throws IOException {
        Path repositoryRoot = repositoryRoot();

        Set<String> files = RealSemanticBenchmarkCorpus.prCorpusFiles(repositoryRoot);

        assertEquals(List.of(), RealSemanticBenchmarkCorpus.missingRelevantPaths(files));
    }

    private Path fixtureWithEveryRelevantPath() throws IOException {
        Path root = temporaryDirectory.resolve("snapshot");
        for (RealSemanticBenchmarkCorpus.QueryCase queryCase : RealSemanticBenchmarkCorpus.queries()) {
            for (String relevantPath : queryCase.relevantPaths()) {
                write(root, relevantPath);
            }
        }
        write(root, "core/src/main/java/com/nexus/search/SearchService.java");
        return root;
    }

    private static void write(Path root, String relativePath) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "contenu " + relativePath);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("mvnw")) && Files.isDirectory(current.resolve("docs/developer"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Racine du dépôt NEXUS introuvable depuis " + Path.of("").toAbsolutePath());
    }
}
