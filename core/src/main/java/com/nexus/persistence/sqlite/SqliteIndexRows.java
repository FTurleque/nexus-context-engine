package com.nexus.persistence.sqlite;

import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteIndexRows {
    private SqliteIndexRows() { }
    static Map<String, IndexedFile> readFiles(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            Map<String, IndexedFile> files = new LinkedHashMap<>();
            while (resultSet.next()) {
                IndexedFile file = new IndexedFile(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("project_id")),
                        resultSet.getString(RELATIVE_PATH_COLUMN),
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
    }

    static List<IndexedSymbol> readSymbols(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<IndexedSymbol> symbols = new ArrayList<>();
            while (resultSet.next()) {
                symbols.add(new IndexedSymbol(
                        resultSet.getString(RELATIVE_PATH_COLUMN),
                        new CodeSymbol(
                                SymbolKind.valueOf(resultSet.getString("kind")),
                                resultSet.getString("name"),
                                resultSet.getString(QUALIFIED_NAME_COLUMN),
                                resultSet.getString("signature"),
                                resultSet.getInt("start_line"),
                                resultSet.getInt("end_line"),
                                resultSet.getString("source_provider"))));
            }
            return List.copyOf(symbols);
        }
    }

    static List<SymbolRelation> readRelations(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<SymbolRelation> relations = new ArrayList<>();
            while (resultSet.next()) {
                relations.add(new SymbolRelation(
                        RelationKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("source_ref"),
                        resultSet.getString("target_ref"),
                        resultSet.getDouble("confidence"),
                        resultSet.getString("source_provider")));
            }
            return List.copyOf(relations);
        }
    }
}
