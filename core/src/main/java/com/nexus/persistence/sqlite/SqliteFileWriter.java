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
            database.writeTransaction("apply index changes for project " + projectId,
                    connection -> {
                        writeChanges(connection, projectId, updates, removedPaths);
                    });
        } catch (SQLException exception) {
            throw persistence("Impossible de mettre à jour l'index SQLite du projet " + projectId, exception);
        }
    }

    /** Corps de la transaction d'indexation ; la transaction reste possédée par l'appelant (ADR-0051). */
    static void writeChanges(
            Connection connection,
            UUID projectId,
            List<IndexedFileUpdate> updates,
            Set<String> removedPaths) throws SQLException {
        deleteRemovedFiles(connection, projectId, removedPaths);
        if (!updates.isEmpty()) {
            // Instructions préparées une fois par transaction, réutilisées pour chaque fichier.
            try (FileStatements statements = new FileStatements(connection, projectId)) {
                for (IndexedFileUpdate update : updates) {
                    long fileId = statements.upsertFile(update.file());
                    statements.replaceAnalysis(fileId, update);
                }
            }
        }
        if (!updates.isEmpty() || !removedPaths.isEmpty()) {
            bumpGeneration(connection, projectId);
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

    private static final String UPSERT_FILE_SQL = """
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
            RETURNING id
            """;
    private static final String DELETE_RELATIONS_SQL =
            "DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?";
    private static final String DELETE_SYMBOLS_SQL =
            "DELETE FROM symbols WHERE file_id = ? AND source_provider = ?";

    /**
     * Instructions d'écriture d'un fichier, préparées une fois pour toute la transaction. Le nombre
     * d'instructions préparées ne dépend donc pas du nombre de fichiers écrits.
     */
    private static final class FileStatements implements AutoCloseable {
        private final String projectId;
        private final PreparedStatement upsert;
        private final PreparedStatement deleteRelations;
        private final PreparedStatement deleteSymbols;
        private final SqliteBatchInsert symbols;
        private final SqliteBatchInsert relations;

        FileStatements(Connection connection, UUID projectId) throws SQLException {
            this.projectId = projectId.toString();
            PreparedStatement preparedUpsert = null;
            PreparedStatement preparedDeleteRelations = null;
            PreparedStatement preparedDeleteSymbols = null;
            try {
                preparedUpsert = connection.prepareStatement(UPSERT_FILE_SQL);
                preparedDeleteRelations = connection.prepareStatement(DELETE_RELATIONS_SQL);
                preparedDeleteSymbols = connection.prepareStatement(DELETE_SYMBOLS_SQL);
            } catch (SQLException failure) {
                closeQuietly(failure, preparedUpsert, preparedDeleteRelations, preparedDeleteSymbols);
                throw failure;
            }
            this.upsert = preparedUpsert;
            this.deleteRelations = preparedDeleteRelations;
            this.deleteSymbols = preparedDeleteSymbols;
            this.symbols = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS);
            this.relations = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.RELATIONS);
        }

        /** Upsert du fichier ; l'identifiant vient de {@code RETURNING id}, sans relecture. */
        long upsertFile(ScannedFile file) throws SQLException {
            upsert.setString(1, projectId);
            upsert.setString(2, file.relativePath());
            upsert.setString(3, file.language());
            upsert.setLong(4, file.sizeBytes());
            upsert.setString(5, file.contentHash());
            upsert.setString(6, file.modifiedAt().toString());
            upsert.setInt(7, file.estimatedTokens());
            upsert.setString(8, file.category().name());
            try (ResultSet resultSet = upsert.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Fichier indexé sans identifiant après upsert : " + file.relativePath());
                }
                return resultSet.getLong(1);
            }
        }

        void replaceAnalysis(long fileId, IndexedFileUpdate update) throws SQLException {
            deleteRelations.setLong(1, fileId);
            deleteRelations.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteRelations.executeUpdate();
            deleteSymbols.setLong(1, fileId);
            deleteSymbols.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteSymbols.executeUpdate();
            for (CodeSymbol symbol : update.analysis().symbols()) {
                symbols.add(fileId, symbol.kind().name(), symbol.name(), symbol.qualifiedName(),
                        symbol.signature(), symbol.startLine(), symbol.endLine(), symbol.sourceProvider());
            }
            symbols.flush();
            for (SymbolRelation relation : update.analysis().relations()) {
                relations.add(projectId, fileId, relation.kind().name(), relation.source(),
                        relation.target(), relation.confidence(), relation.sourceProvider());
            }
            relations.flush();
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                symbols.close();
            } catch (SQLException closeFailure) {
                failure = closeFailure;
            }
            try {
                relations.close();
            } catch (SQLException closeFailure) {
                failure = merge(failure, closeFailure);
            }
            for (PreparedStatement statement : new PreparedStatement[] {upsert, deleteRelations, deleteSymbols}) {
                try {
                    statement.close();
                } catch (SQLException closeFailure) {
                    failure = merge(failure, closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static SQLException merge(SQLException primary, SQLException secondary) {
            if (primary == null) {
                return secondary;
            }
            primary.addSuppressed(secondary);
            return primary;
        }

        private static void closeQuietly(SQLException primary, PreparedStatement... statements) {
            for (PreparedStatement statement : statements) {
                if (statement != null) {
                    try {
                        statement.close();
                    } catch (SQLException closeFailure) {
                        primary.addSuppressed(closeFailure);
                    }
                }
            }
        }
    }
}
