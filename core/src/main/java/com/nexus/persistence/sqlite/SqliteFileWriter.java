package com.nexus.persistence.sqlite;

import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolRelation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;
import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteFileWriter {
    private final SqliteDatabase database;
    SqliteFileWriter(SqliteDatabase database) { this.database = database; }

    void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths) {
        try {
            database.writeTransaction("apply index changes for project " + projectId, connection -> {
                deleteRemovedFiles(connection, projectId, removedPaths);
                for (IndexedFileUpdate update : updates) {
                    long fileId = upsertFile(connection, projectId, update.file());
                    replaceAnalysis(connection, projectId, fileId, update);
                }
                if (!updates.isEmpty() || !removedPaths.isEmpty()) {
                    bumpGeneration(connection, projectId);
                }
            });
        } catch (SQLException exception) {
            throw persistence("Impossible de mettre à jour l'index SQLite du projet " + projectId, exception);
        }
    }

    static void deleteRemovedFiles(Connection connection, UUID projectId, Set<String> removedPaths)
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

    static long upsertFile(Connection connection, UUID projectId, ScannedFile file) throws SQLException {
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

    static void replaceAnalysis(
            Connection connection,
            UUID projectId,
            long fileId,
            IndexedFileUpdate update) throws SQLException {
        try (PreparedStatement deleteRelations = connection.prepareStatement(
                "DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?");
             PreparedStatement deleteSymbols = connection.prepareStatement(
                     "DELETE FROM symbols WHERE file_id = ? AND source_provider = ?")) {
            deleteRelations.setLong(1, fileId);
            deleteRelations.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteRelations.executeUpdate();
            deleteSymbols.setLong(1, fileId);
            deleteSymbols.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteSymbols.executeUpdate();
        }
        insertSymbols(connection, fileId, update.analysis().symbols());
        insertRelations(connection, projectId, fileId, update.analysis().relations());
    }

    static void insertSymbols(Connection connection, long fileId, List<CodeSymbol> symbols)
            throws SQLException {
        if (symbols.isEmpty()) {
            return;
        }
        try (var statement = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS)) {
            for (CodeSymbol symbol : symbols) {
                statement.add(fileId, symbol.kind().name(), symbol.name(), symbol.qualifiedName(),
                        symbol.signature(), symbol.startLine(), symbol.endLine(), symbol.sourceProvider());
            }
            statement.flush();
        }
    }

    static void insertRelations(
            Connection connection,
            UUID projectId,
            long fileId,
            List<SymbolRelation> relations) throws SQLException {
        if (relations.isEmpty()) {
            return;
        }
        try (var statement = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.RELATIONS)) {
            for (SymbolRelation relation : relations) {
                statement.add(projectId.toString(), fileId, relation.kind().name(), relation.source(),
                        relation.target(), relation.confidence(), relation.sourceProvider());
            }
            statement.flush();
        }
    }
}
