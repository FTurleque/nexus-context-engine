package com.nexus.persistence.sqlite;

import com.nexus.index.RelationKind;
import com.nexus.index.SymbolRelation;
import com.nexus.search.ResultLimitPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;
import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteRelationQueries {
    private final SqliteDatabase database;
    SqliteRelationQueries(SqliteDatabase database) { this.database = database; }

    List<SymbolRelation> findRelations(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT kind, source_ref, target_ref, confidence, source_provider
                     FROM symbol_relations
                     WHERE project_id = ?
                     ORDER BY source_ref, kind, target_ref, source_provider
                     """)) {
            statement.setString(1, projectId.toString());
            return readRelations(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de lire les relations du projet " + projectId, exception);
        }
    }

    List<SymbolRelation> findImportRelations(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT kind, source_ref, target_ref, confidence, source_provider
                     FROM symbol_relations
                     WHERE project_id = ? AND kind = ?
                     ORDER BY source_ref, target_ref, source_provider
                     """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, RelationKind.IMPORTS.name());
            return readRelations(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de projeter les imports du projet " + projectId, exception);
        }
    }

    List<SymbolRelation> searchRelations(UUID projectId, String symbol, int limit) {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        ResultLimitPolicy.validate(limit);
        String normalized = symbol.trim().toLowerCase(Locale.ROOT);
        if (!usesTrigramIndex(normalized)) {
            return searchRelationsWithLike(projectId, normalized, limit);
        }
        String contains = "%" + escapeLike(normalized) + "%";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT r.kind, r.source_ref, r.target_ref, r.confidence, r.source_provider
                     FROM relation_search_fts search
                     JOIN symbol_relations r ON r.id = search.rowid
                     WHERE relation_search_fts MATCH ?
                       AND r.project_id = ?
                       AND (LOWER(r.source_ref) LIKE ? ESCAPE '\\'
                            OR LOWER(r.target_ref) LIKE ? ESCAPE '\\')
                     ORDER BY r.kind, r.source_ref, r.target_ref, r.source_provider
                     LIMIT ?
                     """)) {
            statement.setString(1, ftsTrigramQuery(normalized));
            statement.setString(2, projectId.toString());
            statement.setString(3, contains);
            statement.setString(4, contains);
            statement.setInt(5, limit);
            return readRelations(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de rechercher les relations du projet " + projectId, exception);
        }
    }

    private List<SymbolRelation> searchRelationsWithLike(UUID projectId, String normalized, int limit) {
        String contains = "%" + escapeLike(normalized) + "%";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT kind, source_ref, target_ref, confidence, source_provider
                     FROM symbol_relations
                     WHERE project_id = ?
                       AND (LOWER(source_ref) LIKE ? ESCAPE '\\'
                            OR LOWER(target_ref) LIKE ? ESCAPE '\\')
                     ORDER BY kind, source_ref, target_ref, source_provider
                     LIMIT ?
                     """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, contains);
            statement.setString(3, contains);
            statement.setInt(4, limit);
            return readRelations(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de rechercher les relations du projet " + projectId, exception);
        }
    }
}
