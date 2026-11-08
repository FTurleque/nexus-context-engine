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
import java.util.UUID;

/**
 * Mise en attente des analyses d'une indexation (V014, ADR DATA-0074). Les écritures sont de
 * courtes transactions possédées par l'appelant ; aucune table canonique ni la génération n'est
 * touchée, de sorte qu'un échec laisse la génération précédente intacte.
 */
final class SqliteIndexStaging {
    private static final String INSERT_STAGED_FILE_SQL = """
            INSERT INTO index_staged_files(
                project_id, relative_path, language, size_bytes,
                content_hash, modified_at, estimated_tokens, category)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private SqliteIndexStaging() { }

    /** Met en attente un lot de fichiers : instructions préparées une fois pour tout le lot. */
    static void stage(Connection connection, UUID projectId, List<IndexedFileUpdate> updates)
            throws SQLException {
        try (PreparedStatement insertFile = connection.prepareStatement(INSERT_STAGED_FILE_SQL);
                SqliteBatchInsert symbols = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.STAGED_SYMBOLS);
                SqliteBatchInsert relations = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.STAGED_RELATIONS)) {
            for (IndexedFileUpdate update : updates) {
                long stagedFileId = insertFile(insertFile, projectId, update.file());
                for (CodeSymbol symbol : update.analysis().symbols()) {
                    symbols.add(stagedFileId, symbol.kind().name(), symbol.name(), symbol.qualifiedName(),
                            symbol.signature(), symbol.startLine(), symbol.endLine(), symbol.sourceProvider());
                }
                symbols.flush();
                for (SymbolRelation relation : update.analysis().relations()) {
                    relations.add(stagedFileId, relation.kind().name(), relation.source(),
                            relation.target(), relation.confidence(), relation.sourceProvider());
                }
                relations.flush();
            }
        }
    }

    /** Écarte toutes les analyses en attente du projet (cascade sur symboles et relations). */
    static void discard(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM index_staged_files WHERE project_id = ?")) {
            statement.setString(1, projectId.toString());
            statement.executeUpdate();
        }
    }

    private static long insertFile(PreparedStatement insertFile, UUID projectId, ScannedFile file)
            throws SQLException {
        insertFile.setString(1, projectId.toString());
        insertFile.setString(2, file.relativePath());
        insertFile.setString(3, file.language());
        insertFile.setLong(4, file.sizeBytes());
        insertFile.setString(5, file.contentHash());
        insertFile.setString(6, file.modifiedAt().toString());
        insertFile.setInt(7, file.estimatedTokens());
        insertFile.setString(8, file.category().name());
        try (ResultSet resultSet = insertFile.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Fichier mis en attente sans identifiant : " + file.relativePath());
            }
            return resultSet.getLong(1);
        }
    }
}
