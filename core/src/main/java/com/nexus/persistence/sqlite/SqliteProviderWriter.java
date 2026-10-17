package com.nexus.persistence.sqlite;

import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolRelation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;
import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteProviderWriter {
    private final SqliteDatabase database;
    SqliteProviderWriter(SqliteDatabase database) { this.database = database; }

    void replaceExternalCodeIntelligence(UUID projectId, CodeIntelligenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (EMBEDDED_SOURCE_PROVIDER.equals(snapshot.sourceProvider())) {
            throw new IllegalArgumentException("Le provider embarqué ne peut pas être remplacé comme index externe");
        }
        try {
            database.writeTransaction(
                    "replace external code intelligence " + snapshot.sourceProvider() + " for project " + projectId,
                    connection -> {
                        Map<String, Long> fileIds = findFileIds(connection, projectId);
                        if (externalSnapshotMatches(connection, projectId, fileIds.keySet(), snapshot)) {
                            return;
                        }
                        deleteProviderData(connection, projectId, snapshot.sourceProvider());
                        insertExternalSymbols(connection, fileIds, snapshot.symbols());
                        insertExternalRelations(connection, projectId, fileIds, snapshot.relations());
                        bumpGeneration(connection, projectId);
                    });
        } catch (SQLException exception) {
            throw persistence(
                    "Impossible de remplacer l'intelligence de code du provider " + snapshot.sourceProvider(),
                    exception);
        }
    }

    static boolean externalSnapshotMatches(
            Connection connection,
            UUID projectId,
            Set<String> indexedPaths,
            CodeIntelligenceSnapshot snapshot) throws SQLException {
        Set<IndexedSymbol> expectedSymbols = snapshot.symbols().stream()
                .filter(symbol -> indexedPaths.contains(symbol.relativePath()))
                .collect(Collectors.toCollection(HashSet::new));
        Set<IndexedRelation> expectedRelations = snapshot.relations().stream()
                .filter(relation -> indexedPaths.contains(relation.relativePath()))
                .collect(Collectors.toCollection(HashSet::new));

        List<IndexedSymbol> persistedSymbols = readProviderSymbols(
                connection, projectId, snapshot.sourceProvider());
        List<IndexedRelation> persistedRelations = readProviderRelations(
                connection, projectId, snapshot.sourceProvider());

        return persistedSymbols.size() == expectedSymbols.size()
                && persistedRelations.size() == expectedRelations.size()
                && new HashSet<>(persistedSymbols).equals(expectedSymbols)
                && new HashSet<>(persistedRelations).equals(expectedRelations);
    }

    static List<IndexedSymbol> readProviderSymbols(
            Connection connection,
            UUID projectId,
            String sourceProvider) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                       s.signature, s.start_line, s.end_line, s.source_provider
                FROM symbols s
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ? AND s.source_provider = ?
                ORDER BY f.relative_path, s.start_line, s.name
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, sourceProvider);
            return readSymbols(statement);
        }
    }

    static List<IndexedRelation> readProviderRelations(
            Connection connection,
            UUID projectId,
            String sourceProvider) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.relative_path, r.kind, r.source_ref, r.target_ref, r.confidence, r.source_provider
                FROM symbol_relations r
                JOIN indexed_files f ON f.id = r.file_id
                WHERE r.project_id = ? AND r.source_provider = ?
                ORDER BY f.relative_path, r.kind, r.source_ref, r.target_ref
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, sourceProvider);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<IndexedRelation> relations = new ArrayList<>();
                while (resultSet.next()) {
                    relations.add(new IndexedRelation(
                            resultSet.getString(RELATIVE_PATH_COLUMN),
                            new SymbolRelation(
                                    RelationKind.valueOf(resultSet.getString("kind")),
                                    resultSet.getString("source_ref"),
                                    resultSet.getString("target_ref"),
                                    resultSet.getDouble("confidence"),
                                    resultSet.getString("source_provider"))));
                }
                return List.copyOf(relations);
            }
        }
    }

    static void deleteProviderData(Connection connection, UUID projectId, String sourceProvider)
            throws SQLException {
        try (PreparedStatement deleteRelations = connection.prepareStatement(
                "DELETE FROM symbol_relations WHERE project_id = ? AND source_provider = ?");
             PreparedStatement deleteSymbols = connection.prepareStatement("""
                     DELETE FROM symbols
                     WHERE source_provider = ?
                       AND file_id IN (SELECT id FROM indexed_files WHERE project_id = ?)
                     """)) {
            deleteRelations.setString(1, projectId.toString());
            deleteRelations.setString(2, sourceProvider);
            deleteRelations.executeUpdate();
            deleteSymbols.setString(1, sourceProvider);
            deleteSymbols.setString(2, projectId.toString());
            deleteSymbols.executeUpdate();
        }
    }

    static Map<String, Long> findFileIds(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, relative_path FROM indexed_files WHERE project_id = ?")) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, Long> fileIds = new LinkedHashMap<>();
                while (resultSet.next()) {
                    fileIds.put(resultSet.getString(RELATIVE_PATH_COLUMN), resultSet.getLong("id"));
                }
                return fileIds;
            }
        }
    }

    static void insertExternalSymbols(
            Connection connection,
            Map<String, Long> fileIds,
            List<IndexedSymbol> symbols) throws SQLException {
        if (symbols.isEmpty()) {
            return;
        }
        try (var statement = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.SYMBOLS)) {
            for (IndexedSymbol indexedSymbol : symbols) {
                Long fileId = fileIds.get(indexedSymbol.relativePath());
                if (fileId == null) {
                    continue;
                }
                CodeSymbol symbol = indexedSymbol.symbol();
                statement.add(fileId, symbol.kind().name(), symbol.name(), symbol.qualifiedName(),
                        symbol.signature(), symbol.startLine(), symbol.endLine(), symbol.sourceProvider());
            }
            statement.flush();
        }
    }

    static void insertExternalRelations(
            Connection connection,
            UUID projectId,
            Map<String, Long> fileIds,
            List<IndexedRelation> relations) throws SQLException {
        if (relations.isEmpty()) {
            return;
        }
        try (var statement = new SqliteBatchInsert(connection, SqliteBatchInsert.Table.RELATIONS)) {
            for (IndexedRelation indexedRelation : relations) {
                Long fileId = fileIds.get(indexedRelation.relativePath());
                if (fileId == null) {
                    continue;
                }
                SymbolRelation relation = indexedRelation.relation();
                statement.add(projectId.toString(), fileId, relation.kind().name(), relation.source(),
                        relation.target(), relation.confidence(), relation.sourceProvider());
            }
            statement.flush();
        }
    }
}
