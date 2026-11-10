package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.IndexedSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PRF-09 : la recherche de symboles est scopée au projet ciblé et interroge les termes en lot,
 * à résultats identiques aux requêtes par terme d'avant la migration V015. AUD-PRF-29 : un projet qui
 * tient au moins la moitié des fichiers de la base parcourt l'index global du préfiltre approché.
 */
class SqliteSymbolSearchScopingTest {

    private static final String[] NAMES = {
            "OrderService", "OrderRepository", "InvoicePrinter", "Ordr", "CustomerOrderMapper", "id", "Id2"};
    private static final int LIMIT = 50;

    @TempDir
    Path temporaryDirectory;

    @Test
    void fuzzyPrefilterPlanUsesTheFileScopedIndexWithoutAnyFullSymbolScanAt25And100Projects() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("plan")));
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 25);
            List<String> plan25 = fuzzyPlan(connection, projects.getFirst());            populate(connection, projects, 75);
            List<String> plan100 = fuzzyPlan(connection, projects.getFirst());

            assertTrue(plan25.stream().anyMatch(detail -> detail.contains("idx_symbols_file_fuzzy_prefilter")),
                    () -> "le préfiltre doit utiliser l'index scopé : " + plan25);
            // Aucun parcours complet de la table ou d'un index des symboles.
            assertTrue(plan25.stream().noneMatch(SqliteSymbolSearchScopingTest::scansSymbols),
                    () -> "parcours complet des symboles : " + plan25);
            // Le plan ne change pas quand la base passe de 25 à 100 projets.
            assertEquals(plan25, plan100);
        }
    }

    @Test
    void aProjectAloneInItsBaseUsesTheGlobalPrefilterRangeWithoutAnyFullSymbolScan() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("single-plan")));
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 1);

            assertTrue(SqliteSymbolSearch.usesGlobalFuzzyPrefilter(connection, projects.getFirst()));
            for (String sql : List.of(SqliteSymbolSearch.FUZZY_GLOBAL_BATCH_SQL,
                    SqliteSymbolSearch.FUZZY_GLOBAL_COUNT_ARM_SQL)) {
                List<String> plan = fuzzyPlan(connection, sql, projects.getFirst());
                assertTrue(plan.stream().anyMatch(detail -> detail.contains(
                                "SEARCH s USING INDEX idx_symbols_fuzzy_prefilter (<expr>=? AND <expr>>? AND <expr><?)")),
                        () -> "intervalle de l'index global attendu : " + plan);
                assertTrue(plan.stream().anyMatch(detail -> detail.contains("SEARCH f USING INTEGER PRIMARY KEY")),
                        () -> "le projet se vérifie par la clé du fichier : " + plan);
                assertTrue(plan.stream().noneMatch(SqliteSymbolSearchScopingTest::scansSymbols),
                        () -> "parcours complet des symboles : " + plan);
            }
        }
    }

    @Test
    void theGlobalPrefilterIsChosenFromHalfOfTheIndexedFilesAndNotBelow() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("share")));
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 2);
            UUID target = projects.getFirst();
            // 3 fichiers sur 6 : exactement la moitié de la base.
            assertTrue(SqliteSymbolSearch.usesGlobalFuzzyPrefilter(connection, target));

            insertFile(connection, projects.get(1), "src/Extra.java");
            // 3 fichiers sur 7 : sous la moitié, l'index scopé par fichier reste choisi.
            assertFalse(SqliteSymbolSearch.usesGlobalFuzzyPrefilter(connection, target));
            assertTrue(SqliteSymbolSearch.usesGlobalFuzzyPrefilter(connection, projects.get(1)));
            // Un projet sans fichier n'a aucun candidat : l'index scopé ne parcourt rien.
            assertFalse(SqliteSymbolSearch.usesGlobalFuzzyPrefilter(connection, UUID.randomUUID()));
        }
    }

    @Test
    void aProjectAmongManyKeepsTheFileScopedPrefilter() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("federated-choice")));
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 25);

            assertFalse(SqliteSymbolSearch.usesGlobalFuzzyPrefilter(connection, projects.get(7)));
        }
    }

    @Test
    void globalPrefilterResultsAreIdenticalToThePerTermQueriesForAProjectAloneInItsBase() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("single-results")));
        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 1);
            UUID target = projects.getFirst();
            List<String> queries = List.of("orderservice", "order", "ordr", "id", "customerorder", "absent-token");

            List<IndexedSymbol> expectedConcatenation = new ArrayList<>();
            for (String query : queries) {
                expectedConcatenation.addAll(referenceSearch(connection, target, query));
            }
            assertEquals(expectedConcatenation, repository.searchSymbols(target, queries, LIMIT));
            assertFalse(expectedConcatenation.isEmpty());
        }
    }

    @Test
    void batchedResultsAreIdenticalToThePerTermQueriesOfBeforeV015() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("results")));
        SqliteIndexRepository repository = new SqliteIndexRepository(database);
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 25);
            UUID target = projects.get(7);
            List<String> queries = List.of("orderservice", "order", "ordr", "id", "customerorder", "absent-token");

            List<IndexedSymbol> expectedConcatenation = new ArrayList<>();
            for (String query : queries) {
                List<IndexedSymbol> expected = referenceSearch(connection, target, query);
                assertEquals(expected, repository.searchSymbols(target, query, LIMIT), "requête seule : " + query);
                expectedConcatenation.addAll(expected);
            }
            assertEquals(expectedConcatenation, repository.searchSymbols(target, queries, LIMIT));
            assertTrue(expectedConcatenation.stream().allMatch(symbol -> symbol.symbol().qualifiedName()
                    .startsWith("demo.p7.")), "seuls les symboles du projet ciblé sont rendus");
            assertFalse(expectedConcatenation.isEmpty());
        }
    }

    @Test
    void numberOfSqlStatementsDoesNotGrowWithTheNumberOfTerms() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("statements")));
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 5);
            UUID target = projects.getFirst();

            AtomicInteger oneTerm = new AtomicInteger();
            SqliteSymbolSearch.searchSymbols(counting(connection, oneTerm), target, List.of("orderservice"), LIMIT);

            AtomicInteger eightTerms = new AtomicInteger();
            SqliteSymbolSearch.searchSymbols(counting(connection, eightTerms), target, List.of(
                    "orderservice", "orderrepository", "invoiceprinter", "customerorder",
                    "mapper", "service", "printer", "repository"), LIMIT);

            assertTrue(oneTerm.get() > 0);
            assertEquals(oneTerm.get(), eightTerms.get());
        }
    }

    @Test
    void rejectsMoreQueriesThanTheBatchBoundAndAcceptsExactlyTheBound() throws Exception {
        SqliteDatabase database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("bound")));
        SqliteSymbolSearch search = new SqliteSymbolSearch(database);
        List<UUID> projects = new ArrayList<>();
        try (Connection connection = database.openConnection()) {
            populate(connection, projects, 1);
        }
        List<String> atBound = new ArrayList<>();
        for (int index = 0; index < SqliteSymbolSearch.MAX_QUERIES_PER_BATCH; index++) {
            atBound.add("term" + index + "x");
        }
        search.searchSymbols(projects.getFirst(), atBound, LIMIT);

        List<String> overBound = new ArrayList<>(atBound);
        overBound.add("onemoreterm");
        assertThrows(IllegalArgumentException.class, () -> search.searchSymbols(projects.getFirst(), overBound, LIMIT));
        assertThrows(IllegalArgumentException.class,
                () -> search.searchSymbols(projects.getFirst(), List.of("  "), LIMIT));
    }

    // --- Oracle : requêtes par terme d'avant AUD-PRF-09, sans INDEXED BY ----------------------------

    private static List<IndexedSymbol> referenceSearch(Connection connection, UUID projectId, String normalized)
            throws SQLException {
        String contains = "%" + SqliteIndexSql.escapeLike(normalized) + "%";
        String prefix = SqliteIndexSql.escapeLike(normalized) + "%";
        if (!SqliteIndexSql.usesTrigramIndex(normalized)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                           s.signature, s.start_line, s.end_line, s.source_provider
                    FROM symbols s
                    JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ?
                      AND (LOWER(s.name) LIKE ? ESCAPE '\\'
                           OR LOWER(s.qualified_name) LIKE ? ESCAPE '\\'
                           OR (SUBSTR(LOWER(s.name), 1, 1) = ? AND ABS(LENGTH(s.name) - ?) <= 3))
                    ORDER BY CASE
                                 WHEN LOWER(s.name) = ? THEN 0
                                 WHEN LOWER(s.qualified_name) = ? THEN 1
                                 WHEN LOWER(s.name) LIKE ? ESCAPE '\\' THEN 2
                                 ELSE 3 END,
                             s.qualified_name, f.relative_path, s.start_line, s.source_provider
                    LIMIT ?
                    """)) {
                statement.setString(1, projectId.toString());
                statement.setString(2, contains);
                statement.setString(3, contains);
                statement.setString(4, SqliteIndexSql.firstCodePoint(normalized));
                statement.setInt(5, normalized.codePointCount(0, normalized.length()));
                statement.setString(6, normalized);
                statement.setString(7, normalized);
                statement.setString(8, prefix);
                statement.setInt(9, LIMIT);
                return SqliteIndexRows.readSymbols(statement);
            }
        }
        List<IndexedSymbol> trigram;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                       s.signature, s.start_line, s.end_line, s.source_provider
                FROM symbol_search_fts search
                JOIN symbols s ON s.id = search.rowid
                JOIN indexed_files f ON f.id = s.file_id
                WHERE symbol_search_fts MATCH ?
                  AND f.project_id = ?
                  AND (LOWER(s.name) LIKE ? ESCAPE '\\' OR LOWER(s.qualified_name) LIKE ? ESCAPE '\\')
                ORDER BY CASE
                             WHEN LOWER(s.name) = ? THEN 0
                             WHEN LOWER(s.qualified_name) = ? THEN 1
                             WHEN LOWER(s.name) LIKE ? ESCAPE '\\' THEN 2
                             ELSE 3 END,
                         s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?
                """)) {
            statement.setString(1, SqliteIndexSql.ftsTrigramQuery(normalized));
            statement.setString(2, projectId.toString());
            statement.setString(3, contains);
            statement.setString(4, contains);
            statement.setString(5, normalized);
            statement.setString(6, normalized);
            statement.setString(7, prefix);
            statement.setInt(8, LIMIT);
            trigram = SqliteIndexRows.readSymbols(statement);
        }
        int length = normalized.codePointCount(0, normalized.length());
        List<IndexedSymbol> fuzzy;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                       s.signature, s.start_line, s.end_line, s.source_provider
                FROM symbols s
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, SqliteIndexSql.firstCodePoint(normalized));
            statement.setInt(3, Math.max(0, length - 3));
            statement.setInt(4, length + 3);
            statement.setInt(5, LIMIT);
            fuzzy = SqliteIndexRows.readSymbols(statement);
        }
        return SqliteSymbolSearch.mergeSymbolCandidates(normalized, trigram, fuzzy, LIMIT);
    }

    // --- Plan et comptage ------------------------------------------------------------------------

    private static List<String> fuzzyPlan(Connection connection, UUID projectId) throws SQLException {
        return fuzzyPlan(connection, SqliteSymbolSearch.FUZZY_BATCH_SQL, projectId);
    }

    /** Plan d'un bras du préfiltre approché ; les bras de récupération et de comptage ont les mêmes paramètres. */
    private static List<String> fuzzyPlan(Connection connection, String sql, UUID projectId) throws SQLException {
        List<String> details = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            statement.setInt(1, 0);
            statement.setString(2, projectId.toString());
            statement.setString(3, "o");
            statement.setInt(4, 9);
            statement.setInt(5, 15);
            statement.setInt(6, LIMIT);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    details.add(resultSet.getString("detail"));
                }
            }
        }
        return details;
    }

    /** Vrai pour un détail de plan qui parcourt la table symbols ou un de ses index en entier. */
    private static boolean scansSymbols(String detail) {
        String upper = detail.toUpperCase(Locale.ROOT);
        return upper.startsWith("SCAN ") && (upper.contains(" S ") || upper.endsWith(" S")
                || upper.contains("SYMBOLS") || upper.contains("IDX_SYMBOLS"));
    }

    private static Connection counting(Connection connection, AtomicInteger statements) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("prepareStatement")) {
                statements.incrementAndGet();
            }
            try {
                return method.invoke(connection, arguments);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                SqliteSymbolSearchScopingTest.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    // --- Données ---------------------------------------------------------------------------------

    /** Ajoute {@code count} projets de 3 fichiers portant les mêmes noms de symboles, packages distincts. */
    private void populate(Connection connection, List<UUID> projects, int count) throws SQLException {
        connection.setAutoCommit(false);
        try {
            insertProjects(connection, projects, count);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void insertProjects(Connection connection, List<UUID> projects, int count) throws SQLException {
        int first = projects.size();
        for (int index = first; index < first + count; index++) {
            UUID projectId = UUID.randomUUID();
            projects.add(projectId);
            try (PreparedStatement project = connection.prepareStatement("""
                    INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                    VALUES (?, ?, ?, 'LOCAL', ?, 'READY')
                    """)) {
                project.setString(1, projectId.toString());
                project.setString(2, "project-" + index);
                project.setString(3, temporaryDirectory.resolve("root-" + index).toString());
                project.setString(4, Instant.EPOCH.toString());
                project.executeUpdate();
            }
            for (int file = 0; file < 3; file++) {
                long fileId = insertFile(connection, projectId, "src/F" + file + ".java");
                for (String name : new LinkedHashSet<>(List.of(NAMES))) {
                    insertSymbol(connection, fileId, name, "demo.p" + index + "." + name);
                }
            }
            try (PreparedStatement generation = connection.prepareStatement("""
                    INSERT INTO project_index_generations(project_id, generation) VALUES (?, 1)
                    ON CONFLICT(project_id) DO UPDATE SET generation = generation + 1
                    """)) {
                generation.setString(1, projectId.toString());
                generation.executeUpdate();
            }
        }
    }

    private static long insertFile(Connection connection, UUID projectId, String path) throws SQLException {
        try (PreparedStatement file = connection.prepareStatement("""
                INSERT INTO indexed_files(
                    project_id, relative_path, language, size_bytes,
                    content_hash, modified_at, estimated_tokens, category)
                VALUES (?, ?, 'java', 64, 'hash', ?, 16, 'SOURCE')
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            file.setString(1, projectId.toString());
            file.setString(2, path);
            file.setString(3, Instant.EPOCH.toString());
            file.executeUpdate();
            try (ResultSet keys = file.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void insertSymbol(Connection connection, long fileId, String name, String qualifiedName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbols(
                    file_id, kind, name, qualified_name, signature,
                    start_line, end_line, source_provider)
                VALUES (?, 'CLASS', ?, ?, ?, 1, 2, 'javaparser')
                """)) {
            statement.setLong(1, fileId);
            statement.setString(2, name);
            statement.setString(3, qualifiedName);
            statement.setString(4, "class " + name);
            statement.executeUpdate();
        }
    }
}
