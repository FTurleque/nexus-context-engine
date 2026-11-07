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

    private static final String STAGED_FILES_PAGE_SQL = """
            SELECT id, relative_path, language, size_bytes, content_hash, modified_at,
                   estimated_tokens, category
            FROM index_staged_files
            WHERE project_id = ? AND id > ?
            ORDER BY id
            LIMIT ?
            """;
    private static final String STAGED_SYMBOLS_SQL = """
            SELECT kind, name, qualified_name, signature, start_line, end_line, source_provider
            FROM index_staged_symbols
            WHERE staged_file_id = ?
            ORDER BY id
            """;
    private static final String STAGED_RELATIONS_SQL = """
            SELECT kind, source_ref, target_ref, confidence, source_provider
            FROM index_staged_relations
            WHERE staged_file_id = ?
            ORDER BY id
            """;

    private record StagedFile(long id, String relativePath, String language, long sizeBytes,
            String contentHash, String modifiedAt, int estimatedTokens, String category) { }

    /**
     * Corps de la transaction de publication : applique les analyses mises en attente (V014) aux
     * tables canoniques par pages d'au plus {@link SqliteBatchInsert#ROW_LIMIT} fichiers, vide la mise
     * en attente et incrémente la génération. Les faits écrits sont ceux que
     * {@link #writeChanges} écrirait pour les mêmes analyses. La transaction reste possédée par l'appelant
     * (ADR-0051).
     */
    static void writeStagedChanges(Connection connection, UUID projectId, Set<String> removedPaths)
            throws SQLException {
        deleteRemovedFiles(connection, projectId, removedPaths);
        boolean anyStaged = false;
        try (FileStatements statements = new FileStatements(connection, projectId);
                PreparedStatement page = connection.prepareStatement(STAGED_FILES_PAGE_SQL);
                PreparedStatement stagedSymbols = connection.prepareStatement(STAGED_SYMBOLS_SQL);
                PreparedStatement stagedRelations = connection.prepareStatement(STAGED_RELATIONS_SQL)) {
            long lastId = 0L;
            List<StagedFile> files = readStagedPage(page, projectId, lastId);
            while (!files.isEmpty()) {
                anyStaged = true;
                for (StagedFile file : files) {
                    long fileId = statements.upsertFile(file.relativePath(), file.language(),
                            file.sizeBytes(), file.contentHash(), file.modifiedAt(),
                            file.estimatedTokens(), file.category());
                    statements.deleteAnalysis(fileId);
                    copyStagedSymbols(statements, stagedSymbols, file.id(), fileId);
                    copyStagedRelations(statements, stagedRelations, file.id(), fileId);
                    lastId = file.id();
                }
                files = readStagedPage(page, projectId, lastId);
            }
        }
        SqliteIndexStaging.discard(connection, projectId);
        if (anyStaged || !removedPaths.isEmpty()) {
            bumpGeneration(connection, projectId);
        }
    }

    private static List<StagedFile> readStagedPage(PreparedStatement page, UUID projectId, long afterId)
            throws SQLException {
        page.setString(1, projectId.toString());
        page.setLong(2, afterId);
        page.setInt(3, SqliteBatchInsert.ROW_LIMIT);
        List<StagedFile> files = new java.util.ArrayList<>(SqliteBatchInsert.ROW_LIMIT);
        try (ResultSet resultSet = page.executeQuery()) {
            while (resultSet.next()) {
                files.add(new StagedFile(resultSet.getLong(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getLong(4), resultSet.getString(5),
                        resultSet.getString(6), resultSet.getInt(7), resultSet.getString(8)));
            }
        }
        return files;
    }

    private static void copyStagedSymbols(FileStatements statements, PreparedStatement query,
            long stagedFileId, long fileId) throws SQLException {
        query.setLong(1, stagedFileId);
        try (ResultSet resultSet = query.executeQuery()) {
            while (resultSet.next()) {
                statements.addSymbol(fileId, resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getInt(5),
                        resultSet.getInt(6), resultSet.getString(7));
            }
        }
        statements.flushSymbols();
    }

    private static void copyStagedRelations(FileStatements statements, PreparedStatement query,
            long stagedFileId, long fileId) throws SQLException {
        query.setLong(1, stagedFileId);
        try (ResultSet resultSet = query.executeQuery()) {
            while (resultSet.next()) {
                statements.addRelation(fileId, resultSet.getString(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getDouble(4), resultSet.getString(5));
            }
        }
        statements.flushRelations();
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
            return upsertFile(file.relativePath(), file.language(), file.sizeBytes(), file.contentHash(),
                    file.modifiedAt().toString(), file.estimatedTokens(), file.category().name());
        }

        long upsertFile(String relativePath, String language, long sizeBytes, String contentHash,
                String modifiedAt, int estimatedTokens, String category) throws SQLException {
            upsert.setString(1, projectId);
            upsert.setString(2, relativePath);
            upsert.setString(3, language);
            upsert.setLong(4, sizeBytes);
            upsert.setString(5, contentHash);
            upsert.setString(6, modifiedAt);
            upsert.setInt(7, estimatedTokens);
            upsert.setString(8, category);
            try (ResultSet resultSet = upsert.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Fichier indexé sans identifiant après upsert : " + relativePath);
                }
                return resultSet.getLong(1);
            }
        }

        void replaceAnalysis(long fileId, IndexedFileUpdate update) throws SQLException {
            deleteAnalysis(fileId);
            for (CodeSymbol symbol : update.analysis().symbols()) {
                addSymbol(fileId, symbol.kind().name(), symbol.name(), symbol.qualifiedName(),
                        symbol.signature(), symbol.startLine(), symbol.endLine(), symbol.sourceProvider());
            }
            flushSymbols();
            for (SymbolRelation relation : update.analysis().relations()) {
                addRelation(fileId, relation.kind().name(), relation.source(),
                        relation.target(), relation.confidence(), relation.sourceProvider());
            }
            flushRelations();
        }

        /** Supprime l'analyse embarquée précédente d'un fichier (relations, puis symboles). */
        void deleteAnalysis(long fileId) throws SQLException {
            deleteRelations.setLong(1, fileId);
            deleteRelations.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteRelations.executeUpdate();
            deleteSymbols.setLong(1, fileId);
            deleteSymbols.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteSymbols.executeUpdate();
        }

        void addSymbol(long fileId, String kind, String name, String qualifiedName, String signature,
                int startLine, int endLine, String sourceProvider) throws SQLException {
            symbols.add(fileId, kind, name, qualifiedName, signature, startLine, endLine, sourceProvider);
        }

        void flushSymbols() throws SQLException {
            symbols.flush();
        }

        void addRelation(long fileId, String kind, String source, String target, double confidence,
                String sourceProvider) throws SQLException {
            relations.add(projectId, fileId, kind, source, target, confidence, sourceProvider);
        }

        void flushRelations() throws SQLException {
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
