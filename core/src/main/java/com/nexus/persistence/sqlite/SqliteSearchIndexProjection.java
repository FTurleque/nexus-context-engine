package com.nexus.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Maintient les index FTS5 dérivés dans la même transaction que les faits
 * canoniques SQLite. Les écritures sont volontairement faites par
 * INSERT ... SELECT afin d'éviter le coût d'un trigger FTS par ligne.
 */
final class SqliteSearchIndexProjection {

    private SqliteSearchIndexProjection() {
    }

    static void deleteFileProvider(Connection connection, long fileId, String sourceProvider)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        try (PreparedStatement symbols = connection.prepareStatement("""
                DELETE FROM symbol_search_fts
                WHERE rowid IN (
                    SELECT id FROM symbols
                    WHERE file_id = ? AND source_provider = ?
                )
                """);
             PreparedStatement relations = connection.prepareStatement("""
                DELETE FROM relation_search_fts
                WHERE rowid IN (
                    SELECT id FROM symbol_relations
                    WHERE file_id = ? AND source_provider = ?
                )
                """)) {
            symbols.setLong(1, fileId);
            symbols.setString(2, sourceProvider);
            symbols.executeUpdate();
            relations.setLong(1, fileId);
            relations.setString(2, sourceProvider);
            relations.executeUpdate();
        }
    }

    static void indexFileProvider(
            Connection connection,
            UUID projectId,
            long fileId,
            String sourceProvider) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        try (PreparedStatement symbols = connection.prepareStatement("""
                INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
                SELECT s.id, s.name, s.qualified_name, ?
                FROM symbols s
                WHERE s.file_id = ? AND s.source_provider = ?
                """);
             PreparedStatement relations = connection.prepareStatement("""
                INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
                SELECT r.id, r.source_ref, r.target_ref, r.project_id
                FROM symbol_relations r
                WHERE r.file_id = ? AND r.source_provider = ?
                """)) {
            symbols.setString(1, projectId.toString());
            symbols.setLong(2, fileId);
            symbols.setString(3, sourceProvider);
            symbols.executeUpdate();
            relations.setLong(1, fileId);
            relations.setString(2, sourceProvider);
            relations.executeUpdate();
        }
    }

    static void deleteProjectProvider(Connection connection, UUID projectId, String sourceProvider)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        try (PreparedStatement symbols = connection.prepareStatement("""
                DELETE FROM symbol_search_fts
                WHERE rowid IN (
                    SELECT s.id
                    FROM symbols s
                    JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ? AND s.source_provider = ?
                )
                """);
             PreparedStatement relations = connection.prepareStatement("""
                DELETE FROM relation_search_fts
                WHERE rowid IN (
                    SELECT id FROM symbol_relations
                    WHERE project_id = ? AND source_provider = ?
                )
                """)) {
            symbols.setString(1, projectId.toString());
            symbols.setString(2, sourceProvider);
            symbols.executeUpdate();
            relations.setString(1, projectId.toString());
            relations.setString(2, sourceProvider);
            relations.executeUpdate();
        }
    }

    static void indexProjectProvider(Connection connection, UUID projectId, String sourceProvider)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        try (PreparedStatement symbols = connection.prepareStatement("""
                INSERT INTO symbol_search_fts(rowid, name, qualified_name, project_id)
                SELECT s.id, s.name, s.qualified_name, f.project_id
                FROM symbols s
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ? AND s.source_provider = ?
                """);
             PreparedStatement relations = connection.prepareStatement("""
                INSERT INTO relation_search_fts(rowid, source_ref, target_ref, project_id)
                SELECT id, source_ref, target_ref, project_id
                FROM symbol_relations
                WHERE project_id = ? AND source_provider = ?
                """)) {
            symbols.setString(1, projectId.toString());
            symbols.setString(2, sourceProvider);
            symbols.executeUpdate();
            relations.setString(1, projectId.toString());
            relations.setString(2, sourceProvider);
            relations.executeUpdate();
        }
    }

    static void deleteRemovedFiles(Connection connection, UUID projectId, String relativePath)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(relativePath, "relativePath");
        try (PreparedStatement symbols = connection.prepareStatement("""
                DELETE FROM symbol_search_fts
                WHERE rowid IN (
                    SELECT s.id
                    FROM symbols s
                    JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ? AND f.relative_path = ?
                )
                """);
             PreparedStatement relations = connection.prepareStatement("""
                DELETE FROM relation_search_fts
                WHERE rowid IN (
                    SELECT r.id
                    FROM symbol_relations r
                    JOIN indexed_files f ON f.id = r.file_id
                    WHERE f.project_id = ? AND f.relative_path = ?
                )
                """)) {
            symbols.setString(1, projectId.toString());
            symbols.setString(2, relativePath);
            symbols.executeUpdate();
            relations.setString(1, projectId.toString());
            relations.setString(2, relativePath);
            relations.executeUpdate();
        }
    }
}
