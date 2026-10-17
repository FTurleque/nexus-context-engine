package com.nexus.persistence.sqlite;

import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedSymbol;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;
import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteFileQueries {
    private final SqliteDatabase database;
    SqliteFileQueries(SqliteDatabase database) { this.database = database; }

    Map<String, IndexedFile> findFiles(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, project_id, relative_path, language, size_bytes,
                            content_hash, modified_at, estimated_tokens, category
                     FROM indexed_files
                     WHERE project_id = ?
                     ORDER BY relative_path
                     """)) {
            statement.setString(1, projectId.toString());
            return readFiles(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de lire les fichiers indexés du projet " + projectId, exception);
        }
    }

    Map<String, IndexedFile> findFiles(UUID projectId, Set<String> relativePaths) {
        Objects.requireNonNull(relativePaths, "relativePaths");
        if (relativePaths.isEmpty()) {
            return Map.of();
        }
        relativePaths.forEach(path -> Objects.requireNonNull(path, "relativePaths must not contain null"));
        List<String> paths = relativePaths.stream().sorted().toList();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT f.id, f.project_id, f.relative_path, f.language, f.size_bytes,
                            f.content_hash, f.modified_at, f.estimated_tokens, f.category
                     FROM json_each(?) AS requested
                     CROSS JOIN indexed_files f
                     WHERE f.project_id = ? AND f.relative_path = requested.value
                     ORDER BY f.relative_path
                     """)) {
            statement.setString(1, serializePaths(paths));
            statement.setString(2, projectId.toString());
            return readFiles(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de lire les fichiers ciblés du projet " + projectId, exception);
        }
    }

    List<IndexedSymbol> findSymbols(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                            s.signature, s.start_line, s.end_line, s.source_provider
                     FROM symbols s
                     JOIN indexed_files f ON f.id = s.file_id
                     WHERE f.project_id = ?
                     ORDER BY f.relative_path, s.start_line, s.name, s.source_provider
                     """)) {
            statement.setString(1, projectId.toString());
            return readSymbols(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de lire les symboles du projet " + projectId, exception);
        }
    }

    Map<String, String> findTypeOwners(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT s.qualified_name, MIN(f.relative_path) AS relative_path
                     FROM symbols s
                     JOIN indexed_files f ON f.id = s.file_id
                     WHERE f.project_id = ?
                       AND s.kind IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM', 'ANNOTATION', 'TYPE')
                     GROUP BY s.qualified_name
                     ORDER BY s.qualified_name
                     """)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, String> owners = new LinkedHashMap<>();
                while (resultSet.next()) {
                    owners.put(resultSet.getString(QUALIFIED_NAME_COLUMN), resultSet.getString(RELATIVE_PATH_COLUMN));
                }
                return Map.copyOf(owners);
            }
        } catch (SQLException exception) {
            throw persistence("Impossible de projeter les propriétaires de types du projet " + projectId, exception);
        }
    }

    Set<String> findExternalProviders(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT source_provider
                     FROM (
                         SELECT DISTINCT s.source_provider AS source_provider
                         FROM symbols s
                         JOIN indexed_files f ON f.id = s.file_id
                         WHERE f.project_id = ? AND s.source_provider <> ?
                         UNION
                         SELECT DISTINCT r.source_provider AS source_provider
                         FROM symbol_relations r
                         WHERE r.project_id = ? AND r.source_provider <> ?
                     )
                     ORDER BY source_provider
                     """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, EMBEDDED_SOURCE_PROVIDER);
            statement.setString(3, projectId.toString());
            statement.setString(4, EMBEDDED_SOURCE_PROVIDER);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> providers = new LinkedHashSet<>();
                while (resultSet.next()) {
                    providers.add(resultSet.getString("source_provider"));
                }
                return Collections.unmodifiableSet(providers);
            }
        } catch (SQLException exception) {
            throw persistence("Impossible de lire les providers externes du projet " + projectId, exception);
        }
    }

    IndexStatistics statistics(UUID projectId) {
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
            throw persistence("Impossible de calculer les statistiques du projet " + projectId, exception);
        }
    }

    long generation(UUID projectId) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT generation
                     FROM project_index_generations
                     WHERE project_id = ?
                     """)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        } catch (SQLException exception) {
            throw persistence("Impossible de lire la génération d'index du projet " + projectId, exception);
        }
    }
}
