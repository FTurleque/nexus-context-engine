package com.nexus.index;

import com.nexus.paths.RepositoryPath;

import com.nexus.config.NexusPaths;
import com.nexus.context.ContextFragmentFactory;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.*;
import com.nexus.project.ProjectRegistry;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.lucene.*;
import com.nexus.token.HeuristicTokenEstimator;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PosixPathIdentityIntegrationTest {
    @TempDir Path temporary;


    @Test void ignoreRulesDoNotTurnPosixBackslashesIntoDirectories() throws Exception {
        assumeTrue(temporary.getFileSystem().getSeparator().equals("/"));
        Path root = Files.createDirectory(temporary.resolve("ignore-project"));
        Files.createDirectory(root.resolve("a"));
        Files.writeString(root.resolve("a\\b.java"), "class Backslash {}");
        Files.writeString(root.resolve("a/b.java"), "class Slash {}");
        for (String ignore : List.of(".gitignore", ".nexusignore")) {
            Files.writeString(root.resolve(ignore), "a/b.java\n");
            var paths = new ProjectScanner().scan(root).stream().map(ScannedFile::relativePath).toList();
            assertTrue(paths.contains("a\\b.java"));
            assertFalse(paths.contains("a/b.java"));
            Files.delete(root.resolve(ignore));
        }
    }

    @Test void sqliteLuceneFingerprintAndMaterializerKeepBothPhysicalFiles() throws Exception {
        assumeTrue(temporary.getFileSystem().getSeparator().equals("/"), "POSIX filename required");
        Path root = Files.createDirectory(temporary.resolve("project"));
        Files.createDirectory(root.resolve("a"));
        Map<String, String> markers = Map.of("a\\b.java", "BackslashIdentityUniqueMarker",
                "a/b.java", "SlashIdentityUniqueMarker");
        for (var entry : markers.entrySet()) Files.writeString(root.resolve(entry.getKey()), "class " + entry.getValue() + " {}");
        var scanned = new ProjectScanner().scan(root);
        assertEquals(markers.keySet(), scanned.stream().map(ScannedFile::relativePath).collect(java.util.stream.Collectors.toSet()));
        assertNotEquals(CanonicalIndexFingerprint.fromScannedFiles(List.of(scanned.getFirst())),
                CanonicalIndexFingerprint.fromScannedFiles(List.of(scanned.getLast())));
        var first = scanned.getFirst();
        var sameContentOtherPath = new ScannedFile(scanned.getLast().absolutePath(), scanned.getLast().relativePath(),
                first.language(), first.sizeBytes(), first.contentHash(), first.modifiedAt(), first.estimatedTokens(), first.category());
        assertNotEquals(CanonicalIndexFingerprint.fromScannedFiles(List.of(first)),
                CanonicalIndexFingerprint.fromScannedFiles(List.of(sameContentOtherPath)));
        assertEquals(CanonicalIndexFingerprint.fromScannedFiles(scanned),
                CanonicalIndexFingerprint.fromScannedFiles(scanned.reversed()));

        var paths = new NexusPaths(temporary.resolve("home"));
        var database = new SqliteDatabase(paths);
        var projects = new SqliteProjectRepository(database);
        var index = new SqliteIndexRepository(database);
        var project = new ProjectRegistry(projects).register(root, "paths");
        try (var lucene = new LuceneSearchIndex(paths)) {
            var service = new ProjectIndexingService(projects, index, new ProjectScanner(),
                    List.of(new JavaParserLanguageAnalyzer()), lucene);
            service.index(project.id());
            var files = index.findFiles(project.id());
            assertEquals(markers.keySet(), files.keySet());
            assertEquals(2, files.values().stream().map(IndexedFile::id).distinct().count());
            assertEquals(CanonicalIndexFingerprint.fromScannedFiles(scanned), CanonicalIndexFingerprint.fromIndexedFiles(files));
            try (var directory = FSDirectory.open(paths.projectLuceneIndex(project.id()));
                 var reader = DirectoryReader.open(directory)) {
                assertEquals(2, reader.numDocs());
            }
            for (var entry : markers.entrySet()) {
                String query = entry.getValue();
                var hits = lucene.search(project.id(), query, 10);
                assertEquals(List.of(entry.getKey()), hits.stream().map(com.nexus.search.LexicalSearchHit::relativePath).toList());
                var candidates = new LuceneFileSearchStrategy(lucene).search(project, query, 10);
                assertEquals(root.resolve(entry.getKey()), candidates.getFirst().path());
                var fragments = new ContextFragmentFactory(new HeuristicTokenEstimator()).create(project, query,
                        candidates.stream().map(c -> new RankedCandidate(c, 1, Map.of(), List.of())).toList(), 500);
                assertEquals(1, fragments.size());
                assertEquals(entry.getKey(), RepositoryPath.encode(fragments.getFirst().path()));
                assertEquals("class " + entry.getValue() + " {}", fragments.getFirst().content().strip());
            }
            assertEquals(0, service.index(project.id()).changedFiles());
            Files.writeString(root.resolve("a\\b.java"), "class ChangedBackslash {}");
            assertEquals(1, service.index(project.id()).changedFiles());
            assertEquals(2, index.findFiles(project.id()).size());
            assertEquals("a/b.java", lucene.search(project.id(), "\"SlashIdentityUniqueMarker\"", 10).getFirst().relativePath());
        }
    }
}
