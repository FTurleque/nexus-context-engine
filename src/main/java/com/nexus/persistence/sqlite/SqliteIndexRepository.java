package com.nexus.persistence.sqlite;

import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.persistence.PersistenceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SqliteIndexRepository implements IndexRepository {

    private static final String SOURCE_PROVIDER = "javaparser";

    private final SqliteDatabase database;

    public SqliteIndexRepository(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public Map<String, IndexedFile> findFiles(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, project_id, relative_path, language, size_bytes,
                            content_hash, modified_at, estimated_tokens, category
                     FROM indexed_files
                     WHERE project_id = ?
                     ORDER BY relative_path
                     """)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, IndexedFile> files = new LinkedHashMap<>();
                while (resultSet.next()) {
                    IndexedFile file = new IndexedFile(
                            resultSet.getLong("id"),
                            UUID.fromString(resultSet.getString("project_id")),
                            resultSet.getString("relative_path"),
                            resultSet.getString("language"),
                            resultSet.getLong("size_bytes"),
                            resultSet.getString("content_hash"),
                            Instant.parse(resultSet.getString("modified_at")),
                            resultSet.getInt("estimated_tokens"),
                            FileCategory.valueOf(resultSet.getString("category")));
                    files.put(file.relativePath(), file);
                }
                return Map.copyOf(files);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de lire les fichiers indexés du projet " + projectId, exception);
        }
    }

    @Override
    public List<IndexedSymbol> findSymbols(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                            s.signature, s.start_line, s.end_line
                     FROM symbols s
                     JOIN indexed_files f ON f.id = s.file_id
                     WHERE f.project_id = ?
                     ORDER BY f.relative_path, s.start_line, s.name
                     """)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<IndexedSymbol> symbols = new ArrayList<>();
                while (resultSet.next()) {
                    symbols.add(new IndexedSymbol(
                            resultSet.getString("relative_path"),
                            new CodeSymbol(
                                    SymbolKind.valueOf(resultSet.getString("kind")),
                                    resultSet.getString("name"),
                                    resultSet.getString("qualified_name"),
                                    resultSet.getString("signature"),
                                    resultSet.getInt("start_line"),
                                    resultSet.getInt("end_line"))));
                }
                return List.copyOf(symbols);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de lire les symboles du projet " + projectId, exception);
        }
    }

    @Override
    public List<SymbolRelation> findRelations(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT kind, source_ref, target_ref
                     FROM symbol_relations
                     WHERE project_id = ?
                     ORDER BY source_ref, kind, target_ref
                     """)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SymbolRelation> relations = new ArrayList<>();
                while (resultSet.next()) {
                    relations.add(new SymbolRelation(
                            RelationKind.valueOf(resultSet.getString("kind")),
                            resultSet.getString("source_ref"),
                            resultSet.getString("target_ref")));
                }
                return List.copyOf(relations);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de lire les relations du projet " + projectId, exception);
        }
    }

    @Override
    public void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths) {
        try (Connection connection = database.openConnection()) {
            boolean initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteRemovedFiles(connection, projectId, removedPaths);
                for (IndexedFileUpdate update : updates) {
                    long fileId = upsertFile(connection, projectId, update.file());
                    replaceAnalysis(connection, projectId, fileId, update);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(initialAutoCommit);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de mettre à jour l'index SQLite du projet " + projectId, exception);
        }
    }

    @Override
    public IndexStatistics statistics(UUID projectId) {
        try (Connection connection = database.openConnection()) {
            long files = count(connection,
                    "SELECT COUNT(*) FROM indexed_files WHERE project_id = ?", projectId);
            long symbols = count(connection, """
                    SELECT COUNT(*)
                    FROM symbols s
                    JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ?
                    """, projectId);
            long relations = count(connection,
                    "SELECT COUNT(*) FROM symbol_relations WHERE project_id = ?", projectId);
            return new IndexStatistics(files, symbols, relations);
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de calculer les statistiques du projet " + projectId, exception);
        }
    }

    private static void deleteRemovedFiles(Connection connection, UUID projectId, Set<String> removedPaths)
            throws SQLException {
        if (removedPaths.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM indexed_files WHERE project_id = ? AND relative_path = ?")) {
            for (String relativePath : removedPaths) {
                statement.setString(1, projectId.toString());
                statement.setString(2, relativePath);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static long upsertFile(Connection connection, UUID projectId, ScannedFile file) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO indexed_files(
                    project_id, relative_path, language, size_bytes,
                    content_hash, modified_at, estimated_tokens, category)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, relative_path) DO UPDATE SET
                    language = excluded.language,
                    size_bytes = excluded.size_bytes,
                    content_hash = excluded.content_hash,
                    modified_at = excluded.modified_at,
                    estimated_tokens = excluded.estimated_tokens,
                    category = excluded.category
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, file.relativePath());
            statement.setString(3, file.language());
            statement.setLong(4, file.sizeBytes());
            statement.setString(5, file.contentHash());
            statement.setString(6, file.modifiedAt().toString());
            statement.setInt(7, file.estimatedTokens());
            statement.setString(8, file.category().name());
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, file.relativePath());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Fichier indexé introuvable après upsert : " + file.relativePath());
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static void replaceAnalysis(
            Connection connection,
            UUID projectId,
            long fileId,
            IndexedFileUpdate update) throws SQLException {
        try (PreparedStatement deleteRelations = connection.prepareStatement(
                "DELETE FROM symbol_relations WHERE file_id = ?");
             PreparedStatement deleteSymbols = connection.prepareStatement(
                     "DELETE FROM symbols WHERE file_id = ?")) {
            deleteRelations.setLong(1, fileId);
            deleteRelations.executeUpdate();
            deleteSymbols.setLong(1, fileId);
            deleteSymbols.executeUpdate();
        }

        insertSymbols(connection, fileId, update.analysis().symbols());
        insertRelations(connection, projectId, fileId, update.analysis().relations());
    }

    private static void insertSymbols(Connection connection, long fileId, List<CodeSymbol> symbols)
            throws SQLException {
        if (symbols.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbols(
                    file_id, kind, name, qualified_name, signature,
                    start_line, end_line, source_provider)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (CodeSymbol symbol : symbols) {
                statement.setLong(1, fileId);
                statement.setString(2, symbol.kind().name());
                statement.setString(3, symbol.name());
                statement.setString(4, symbol.qualifiedName());
                statement.setString(5, symbol.signature());
                statement.setInt(6, symbol.startLine());
                statement.setInt(7, symbol.endLine());
                statement.setString(8, SOURCE_PROVIDER);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertRelations(
            Connection connection,
            UUID projectId,
            long fileId,
            List<SymbolRelation> relations) throws SQLException {
        if (relations.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbol_relations(
                    project_id, file_id, kind, source_ref, target_ref, confidence, source_provider)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (SymbolRelation relation : relations) {
                statement.setString(1, projectId.toString());
                statement.setLong(2, fileId);
                statement.setString(3, relation.kind().name());
                statement.setString(4, relation.source());
                statement.setString(5, relation.target());
                statement.setDouble(6, 1.0d);
                statement.setString(7, SOURCE_PROVIDER);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static long count(Connection connection, String sql, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }
}
