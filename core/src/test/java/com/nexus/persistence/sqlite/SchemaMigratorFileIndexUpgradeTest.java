package com.nexus.persistence.sqlite;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les migrations d'index (V012 : index par fichier de symbol_relations ; V013 : suppression des index
 * préfixes redondants ; V015 : préfiltre approché des symboles scopé par fichier ; V016 : pagination des
 * fichiers en attente ; V017 : préfiltre approché global d'un projet dominant) créent ou suppriment des
 * index sans toucher aux faits canoniques ni au statut d'indexation des projets (AUD-PRF-01, AUD-PRF-15,
 * AUD-PRF-09, AUD-PRF-27, AUD-PRF-29).
 */
class SchemaMigratorFileIndexUpgradeTest {

    private static final Pattern MIGRATION_NAME = Pattern.compile("V(\\d{3})__.*\\.sql");
    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final List<String> CANONICAL_QUERIES = List.of(
            "SELECT * FROM projects ORDER BY id",
            "SELECT * FROM project_index_generations ORDER BY project_id",
            "SELECT * FROM indexed_files ORDER BY id",
            "SELECT * FROM symbols ORDER BY id",
            "SELECT * FROM symbol_relations ORDER BY id");

    @Test
    void v012AddsTheFileProviderIndexWithoutTouchingCanonicalFactsOrProjectStatus() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            buildPopulatedBase(connection, 11);
            assertFalse(indexNames(connection, "symbol_relations").contains("idx_symbol_relations_file_provider"));
            List<String> before = canonicalSnapshot(connection);
            assertEquals(5, before.size());

            SchemaMigrator.migrate(connection);

            assertTrue(indexNames(connection, "symbol_relations").contains("idx_symbol_relations_file_provider"));
            assertEquals(List.of("file_id", "source_provider"),
                    indexColumns(connection, "idx_symbol_relations_file_provider"));
            assertEquals(before, canonicalSnapshot(connection));
            assertEquals("READY", projectStatus(connection));
            assertEquals(7L, projectGeneration(connection));
            assertTrue(appliedVersions(connection).contains(12));
        }
    }

    @Test
    void v013DropsTheRedundantPrefixIndexesWithoutTouchingCanonicalFactsOrProjectStatus() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            buildPopulatedBase(connection, 12);
            assertTrue(indexNames(connection, "symbol_relations").contains("idx_symbol_relations_project"));
            assertTrue(indexNames(connection, "indexed_files").contains("idx_indexed_files_project"));
            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_file"));
            List<String> before = canonicalSnapshot(connection);

            SchemaMigrator.migrate(connection);

            assertFalse(indexNames(connection, "symbol_relations").contains("idx_symbol_relations_project"));
            assertFalse(indexNames(connection, "indexed_files").contains("idx_indexed_files_project"));
            assertFalse(indexNames(connection, "symbols").contains("idx_symbols_file"));
            // Les index plus larges qui servent désormais ces requêtes restent présents.
            assertTrue(indexNames(connection, "symbol_relations").contains("idx_symbol_relations_project_provider"));
            assertTrue(indexNames(connection, "symbol_relations").contains("idx_symbol_relations_file_provider"));
            assertTrue(indexNames(connection, "indexed_files").contains("sqlite_autoindex_indexed_files_1"));
            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_file_provider"));
            assertEquals(before, canonicalSnapshot(connection));
            assertEquals("READY", projectStatus(connection));
            assertEquals(7L, projectGeneration(connection));
            assertTrue(appliedVersions(connection).contains(13));
        }
    }

    @Test
    void v015AddsTheFileScopedFuzzyPrefilterWithoutTouchingCanonicalFactsOrProjectStatus() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            buildPopulatedBase(connection, 14);
            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_fuzzy_prefilter"));
            assertFalse(indexNames(connection, "symbols").contains("idx_symbols_file_fuzzy_prefilter"));
            List<String> before = canonicalSnapshot(connection);

            SchemaMigrator.migrate(connection);

            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_file_fuzzy_prefilter"));
            // La première colonne identifie le fichier, donc le projet par indexed_files.
            assertEquals("file_id", indexColumns(connection, "idx_symbols_file_fuzzy_prefilter").getFirst());
            assertEquals(3, indexColumns(connection, "idx_symbols_file_fuzzy_prefilter").size());
            // L'index par fichier et fournisseur reste ; l'index global est recréé par V017.
            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_file_provider"));
            assertEquals(before, canonicalSnapshot(connection));
            assertEquals("READY", projectStatus(connection));
            assertEquals(7L, projectGeneration(connection));
            assertTrue(appliedVersions(connection).contains(15));
        }
    }

    @Test
    void v016IndexesStagedFilesByProjectWithoutTouchingStagedOrCanonicalFactsOrProjectStatus() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            buildPopulatedBase(connection, 15);
            populateStaged(connection);
            assertFalse(indexNames(connection, "index_staged_files").contains("idx_index_staged_files_project_id"));
            List<String> before = canonicalSnapshot(connection);
            List<String> stagedBefore = stagedSnapshot(connection);

            SchemaMigrator.migrate(connection);

            assertTrue(indexNames(connection, "index_staged_files").contains("idx_index_staged_files_project_id"));
            assertEquals(List.of("project_id", "id"), indexColumns(connection, "idx_index_staged_files_project_id"));
            assertEquals(before, canonicalSnapshot(connection));
            assertEquals(stagedBefore, stagedSnapshot(connection));
            assertEquals("READY", projectStatus(connection));
            assertEquals(7L, projectGeneration(connection));
            assertTrue(appliedVersions(connection).contains(16));
        }
    }

    @Test
    void v017RecreatesTheGlobalFuzzyPrefilterBesideTheFileScopedOneWithoutTouchingCanonicalFacts() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            buildPopulatedBase(connection, 16);
            assertFalse(indexNames(connection, "symbols").contains("idx_symbols_fuzzy_prefilter"));
            List<String> before = canonicalSnapshot(connection);

            SchemaMigrator.migrate(connection);

            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_fuzzy_prefilter"));
            assertEquals(2, indexColumns(connection, "idx_symbols_fuzzy_prefilter").size());
            assertTrue(indexNames(connection, "symbols").contains("idx_symbols_file_fuzzy_prefilter"));
            assertEquals(before, canonicalSnapshot(connection));
            assertEquals("READY", projectStatus(connection));
            assertEquals(7L, projectGeneration(connection));
            assertTrue(appliedVersions(connection).contains(17));
        }
    }

    @Test
    void indexMigrationsAreAppliedOnceAndAReopenDoesNothing() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            buildPopulatedBase(connection, 11);
            SchemaMigrator.migrate(connection);
            List<Integer> versions = appliedVersions(connection);
            List<String> snapshot = canonicalSnapshot(connection);

            SchemaMigrator.migrate(connection);

            assertEquals(versions, appliedVersions(connection));
            assertEquals(snapshot, canonicalSnapshot(connection));
        }
    }

    // --- Fixtures ---------------------------------------------------------------------------

    /** Construit une base peuplée au schéma de la migration {@code lastVersion}, historique inclus. */
    static void buildPopulatedBase(Connection connection, int lastVersion) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE schema_migrations("
                    + "version INTEGER PRIMARY KEY, script_name TEXT NOT NULL, applied_at TEXT NOT NULL)");
        }
        for (Map.Entry<Integer, Path> migration : migrationsUpTo(lastVersion).entrySet()) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : SqlScriptSplitter.split(
                        Files.readString(migration.getValue(), StandardCharsets.UTF_8))) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_migrations(version, script_name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.getKey());
                insert.setString(2, "db/migration/" + migration.getValue().getFileName());
                insert.setString(3, "2026-01-01T00:00:00Z");
                insert.executeUpdate();
            }
        }
        populate(connection);
        markLaterInvalidationsApplied(connection, lastVersion);
    }

    /**
     * Enregistre comme déjà appliquées, sans les exécuter, les migrations d'invalidation postérieures
     * à {@code lastVersion} (V018, V019 et V020 : politique de redaction) : elles repassent les
     * projets en {@code NOT_INDEXED} par construction, alors que ces tests isolent les migrations d'index.
     * L'empreinte vide est complétée par le migrateur sans réappliquer le script. Une migration
     * {@code __invalidate_} ne touche que les générations et le statut des projets, jamais le
     * schéma : sinon ce raccourci masquerait son effet sur les index.
     */
    private static void markLaterInvalidationsApplied(Connection connection, int lastVersion)
            throws SQLException, IOException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO schema_migrations(version, script_name, applied_at) VALUES (?, ?, ?)")) {
            for (Map.Entry<Integer, Path> migration : migrationsUpTo(Integer.MAX_VALUE, false).entrySet()) {
                String name = migration.getValue().getFileName().toString();
                if (migration.getKey() <= lastVersion || !name.contains("__invalidate_")) {
                    continue;
                }
                insert.setInt(1, migration.getKey());
                insert.setString(2, "db/migration/" + name);
                insert.setString(3, "2026-01-01T00:00:00Z");
                insert.executeUpdate();
            }
        }
    }

    private static Map<Integer, Path> migrationsUpTo(int lastVersion) throws IOException {
        return migrationsUpTo(lastVersion, true);
    }

    private static Map<Integer, Path> migrationsUpTo(int lastVersion, boolean complete) throws IOException {
        Map<Integer, Path> migrations = new TreeMap<>();
        try {
            Path directory = Path.of(Objects.requireNonNull(
                    SchemaMigratorFileIndexUpgradeTest.class.getResource("/db/migration"),
                    "Ressource /db/migration absente").toURI());
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Matcher matcher = MIGRATION_NAME.matcher(file.getFileName().toString());
                    if (matcher.matches() && Integer.parseInt(matcher.group(1)) <= lastVersion) {
                        migrations.put(Integer.parseInt(matcher.group(1)), file);
                    }
                }
            }
        } catch (java.net.URISyntaxException exception) {
            throw new IOException(exception);
        }
        if (complete) {
            assertEquals(lastVersion, migrations.size(), "migrations V001 à V" + lastVersion);
        }
        return migrations;
    }

    private static void populate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO projects VALUES ('" + PROJECT_ID
                    + "', 'index-upgrade', '/synthetic/root', 'LOCAL', '2026-01-01T00:00:00Z', 'READY')");
            statement.executeUpdate("INSERT INTO project_index_generations VALUES ('" + PROJECT_ID + "', 7)");
            for (int file = 1; file <= 3; file++) {
                statement.executeUpdate("INSERT INTO indexed_files(id, project_id, relative_path, language, "
                        + "size_bytes, content_hash, modified_at, estimated_tokens, category) VALUES ("
                        + file + ", '" + PROJECT_ID + "', 'src/F" + file + ".java', 'java', 10, 'hash" + file
                        + "', '2026-01-01T00:00:00Z', 5, 'SOURCE')");
                statement.executeUpdate("INSERT INTO symbols(file_id, kind, name, qualified_name, signature, "
                        + "start_line, end_line, source_provider) VALUES (" + file + ", 'CLASS', 'F" + file
                        + "', 'demo.F" + file + "', 'class F" + file + "', 1, 2, 'javaparser')");
                statement.executeUpdate("INSERT INTO symbol_relations(project_id, file_id, kind, source_ref, "
                        + "target_ref, confidence, source_provider) VALUES ('" + PROJECT_ID + "', " + file
                        + ", 'IMPORTS', 'src/F" + file + ".java', 'demo.Target" + file + "', 1.0, 'javaparser')");
                statement.executeUpdate("INSERT INTO symbol_relations(project_id, file_id, kind, source_ref, "
                        + "target_ref, confidence, source_provider) VALUES ('" + PROJECT_ID + "', " + file
                        + ", 'USES', 'demo.F" + file + "', 'demo.External" + file + "', 0.5, 'scip')");
            }
        }
    }

    /** Analyses en attente d'une indexation interrompue entre deux flushs. */
    private static void populateStaged(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (int file = 1; file <= 2; file++) {
                statement.executeUpdate("INSERT INTO index_staged_files(id, project_id, relative_path, language, "
                        + "size_bytes, content_hash, modified_at, estimated_tokens, category) VALUES ("
                        + file + ", '" + PROJECT_ID + "', 'src/Staged" + file + ".java', 'java', 10, 'staged"
                        + file + "', '2026-01-02T00:00:00Z', 5, 'SOURCE')");
                statement.executeUpdate("INSERT INTO index_staged_symbols(staged_file_id, kind, name, "
                        + "qualified_name, signature, start_line, end_line, source_provider) VALUES (" + file
                        + ", 'CLASS', 'Staged" + file + "', 'demo.Staged" + file + "', 'class Staged" + file
                        + "', 1, 2, 'javaparser')");
                statement.executeUpdate("INSERT INTO index_staged_relations(staged_file_id, kind, source_ref, "
                        + "target_ref, confidence, source_provider) VALUES (" + file + ", 'IMPORTS', 'src/Staged"
                        + file + ".java', 'demo.Target" + file + "', 1.0, 'javaparser')");
            }
        }
    }

    private static List<String> stagedSnapshot(Connection connection) throws SQLException {
        List<String> snapshot = new ArrayList<>();
        for (String table : List.of("index_staged_files", "index_staged_symbols", "index_staged_relations")) {
            StringBuilder rows = new StringBuilder();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    for (int column = 1; column <= columns; column++) {
                        rows.append(resultSet.getObject(column)).append('|');
                    }
                    rows.append('\n');
                }
            }
            assertFalse(rows.isEmpty(), table + " doit contenir des analyses en attente");
            snapshot.add(rows.toString());
        }
        return snapshot;
    }

    // --- Lecture du schéma ------------------------------------------------------------------

    static List<String> canonicalSnapshot(Connection connection) throws SQLException {
        List<String> snapshot = new ArrayList<>();
        for (String query : CANONICAL_QUERIES) {
            StringBuilder rows = new StringBuilder();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(query)) {
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    for (int column = 1; column <= columns; column++) {
                        rows.append(resultSet.getObject(column)).append('|');
                    }
                    rows.append('\n');
                }
            }
            snapshot.add(rows.toString());
        }
        return snapshot;
    }

    static List<String> indexNames(Connection connection, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        }
        return names;
    }

    static List<String> indexColumns(Connection connection, String index) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_info(" + index + ")")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    static List<Integer> appliedVersions(Connection connection) throws SQLException {
        List<Integer> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
            while (resultSet.next()) {
                versions.add(resultSet.getInt(1));
            }
        }
        return versions;
    }

    static String projectStatus(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT index_status FROM projects WHERE id = '" + PROJECT_ID + "'")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    static long projectGeneration(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT generation FROM project_index_generations WHERE project_id = '" + PROJECT_ID + "'")) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
