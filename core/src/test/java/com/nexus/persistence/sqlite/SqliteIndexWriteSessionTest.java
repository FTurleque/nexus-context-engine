package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.AnalysisResult;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexWriteSession;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mise en attente des analyses (V014) et publication en une transaction (DATA-0074). */
class SqliteIndexWriteSessionTest {

    /** Plus d'un lot complet de 128 lignes, pour exercer l'instruction pleine et l'instruction de fin. */
    private static final int SYMBOLS_PER_FILE = 130;
    private static final int RELATIONS_PER_FILE = 2;

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagedAnalysesAreInvisibleUntilPublishAndPublishedFactsEqualApplyChanges() throws Exception {
        UUID projectId = UUID.randomUUID();
        List<IndexedFileUpdate> updates = updates(150, "file");

        Fixture direct = fixture("direct", projectId);
        direct.repository().applyChanges(projectId, updates, Set.of());

        Fixture staged = fixture("staged", projectId);
        try (IndexWriteSession session = staged.repository().openWriteSession(projectId)) {
            for (int from = 0; from < updates.size(); from += 50) {
                session.stage(updates.subList(from, from + 50));
                assertEquals(0, staged.repository().findFiles(projectId).size(),
                        "aucun fait canonique n'est visible avant la publication");
                assertEquals(0L, staged.repository().generation(projectId));
            }
            assertEquals(150L, stagedCount(staged.database(), "index_staged_files"));
            assertEquals(150L * SYMBOLS_PER_FILE, stagedCount(staged.database(), "index_staged_symbols"));
            assertEquals(150L * RELATIONS_PER_FILE, stagedCount(staged.database(), "index_staged_relations"));

            session.publish(Set.of());
        }

        assertEquals(direct.repository().statistics(projectId), staged.repository().statistics(projectId));
        assertEquals(1L, staged.repository().generation(projectId));
        assertEquals(direct.repository().generation(projectId), staged.repository().generation(projectId));
        assertEquals(sortedSymbols(direct), sortedSymbols(staged));
        assertEquals(
                direct.repository().findRelations(projectId).stream().map(SymbolRelation::toString).sorted().toList(),
                staged.repository().findRelations(projectId).stream().map(SymbolRelation::toString).sorted().toList());
        assertEquals(0L, stagedCount(staged.database(), "index_staged_files"));
        assertEquals(0L, stagedCount(staged.database(), "index_staged_symbols"));
        assertEquals(0L, stagedCount(staged.database(), "index_staged_relations"));
    }

    @Test
    void closingWithoutPublishDiscardsTheStagedAnalysesAndKeepsThePreviousGeneration() throws Exception {
        UUID projectId = UUID.randomUUID();
        Fixture fixture = fixture("failure", projectId);
        fixture.repository().applyChanges(projectId, updates(2, "previous"), Set.of());
        long generation = fixture.repository().generation(projectId);
        var statistics = fixture.repository().statistics(projectId);

        try (IndexWriteSession session = fixture.repository().openWriteSession(projectId)) {
            session.stage(updates(5, "replacement"));
            session.stage(updates(5, 7, "other"));
            // Pas de publication : l'indexation a échoué avant la barrière.
        }

        assertEquals(generation, fixture.repository().generation(projectId));
        assertEquals(statistics, fixture.repository().statistics(projectId));
        assertEquals(0L, stagedCount(fixture.database(), "index_staged_files"));
        assertEquals(0L, stagedCount(fixture.database(), "index_staged_symbols"));
        assertEquals(0L, stagedCount(fixture.database(), "index_staged_relations"));
        assertTrue(fixture.repository().findSymbols(projectId).stream()
                .noneMatch(symbol -> symbol.symbol().name().startsWith("replacement")));
    }

    @Test
    void openingASessionDiscardsWhatAnInterruptedIndexingLeftBehind() throws Exception {
        UUID projectId = UUID.randomUUID();
        Fixture fixture = fixture("interrupted", projectId);
        IndexWriteSession crashed = fixture.repository().openWriteSession(projectId);
        crashed.stage(updates(3, "leftover"));
        assertEquals(3L, stagedCount(fixture.database(), "index_staged_files"));

        try (IndexWriteSession next = fixture.repository().openWriteSession(projectId)) {
            assertEquals(0L, stagedCount(fixture.database(), "index_staged_files"));
            next.stage(updates(1, "fresh"));
            next.publish(Set.of());
        }

        assertEquals(1, fixture.repository().findFiles(projectId).size());
        assertTrue(fixture.repository().findSymbols(projectId).stream()
                .allMatch(symbol -> symbol.symbol().name().startsWith("fresh")));
    }

    @Test
    void publishAppliesRemovalsAndOnlyBumpsTheGenerationWhenSomethingChanged() throws Exception {
        UUID projectId = UUID.randomUUID();
        Fixture fixture = fixture("removals", projectId);
        fixture.repository().applyChanges(projectId, updates(3, "seed"), Set.of());
        long generation = fixture.repository().generation(projectId);

        try (IndexWriteSession noChange = fixture.repository().openWriteSession(projectId)) {
            noChange.publish(Set.of());
        }
        assertEquals(generation, fixture.repository().generation(projectId));

        try (IndexWriteSession removal = fixture.repository().openWriteSession(projectId)) {
            removal.publish(Set.of("src/File000.java"));
        }
        assertEquals(generation + 1, fixture.repository().generation(projectId));
        assertEquals(2, fixture.repository().findFiles(projectId).size());
        assertEquals(2L * SYMBOLS_PER_FILE, fixture.repository().statistics(projectId).symbols());
    }

    private static List<String> sortedSymbols(Fixture fixture) {
        return fixture.repository().findSymbols(fixture.projectId()).stream()
                .sorted(Comparator.comparing(IndexedSymbol::relativePath)
                        .thenComparing(symbol -> symbol.symbol().qualifiedName()))
                .map(symbol -> symbol.relativePath() + "|" + symbol.symbol())
                .toList();
    }

    private Fixture fixture(String name, UUID projectId) throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve(name)));
        try (Connection connection = database.openConnection();
                PreparedStatement project = connection.prepareStatement("""
                        INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                        VALUES (?, 'session-test', ?, 'LOCAL', NULL, 'READY')
                        """)) {
            project.setString(1, projectId.toString());
            project.setString(2, "/session-test-" + name);
            project.executeUpdate();
        }
        return new Fixture(database, new SqliteIndexRepository(database), projectId);
    }

    private record Fixture(SqliteDatabase database, SqliteIndexRepository repository, UUID projectId) { }

    private static long stagedCount(SqliteDatabase database, String table) throws Exception {
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private List<IndexedFileUpdate> updates(int fileCount, String prefix) {
        return updates(0, fileCount, prefix);
    }

    private List<IndexedFileUpdate> updates(int firstFile, int fileCount, String prefix) {
        List<IndexedFileUpdate> updates = new ArrayList<>();
        for (int file = firstFile; file < firstFile + fileCount; file++) {
            String relativePath = String.format(Locale.ROOT, "src/File%03d.java", file);
            Path absolute = temporaryDirectory.resolve("sources").resolve(relativePath);
            List<CodeSymbol> symbols = new ArrayList<>();
            for (int symbol = 0; symbol < SYMBOLS_PER_FILE; symbol++) {
                String name = prefix + "Symbol" + file + "x" + symbol;
                symbols.add(new CodeSymbol(SymbolKind.CLASS, name, "demo." + name, "class " + name, 1, 2));
            }
            List<SymbolRelation> relations = new ArrayList<>();
            for (int relation = 0; relation < RELATIONS_PER_FILE; relation++) {
                relations.add(new SymbolRelation(RelationKind.USES, "demo." + prefix + file, "demo.Target" + relation));
            }
            updates.add(new IndexedFileUpdate(
                    new ScannedFile(absolute, relativePath, "java", 10, prefix + "-hash-" + file,
                            Instant.EPOCH, 5, FileCategory.SOURCE),
                    new AnalysisResult(absolute, "java", symbols, relations)));
        }
        return updates;
    }
}
