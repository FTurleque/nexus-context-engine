package com.nexus.persistence.sqlite;

import com.nexus.index.RelationKind;
import com.nexus.search.ResultLimitPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
final class SqliteGraphQueries {
    private final SqliteDatabase database;
    SqliteGraphQueries(SqliteDatabase database) { this.database = database; }

    Map<String, Set<String>> findGraphNeighbors(
            UUID projectId,
            Set<String> relativePaths,
            int maxEdges) {
        Objects.requireNonNull(relativePaths, "relativePaths");
        relativePaths.forEach(path -> Objects.requireNonNull(path, "relativePaths must not contain null"));
        ResultLimitPolicy.validateInternalRetrieval(maxEdges);
        if (relativePaths.isEmpty()) {
            return Map.of();
        }

        String requestedPaths = serializePaths(relativePaths.stream().sorted().toList());
        Map<String, Set<String>> neighbors = new LinkedHashMap<>();
        int projectedEdges = 0;

        try (Connection connection = database.openConnection()) {
            List<GraphImportTarget> outgoingRelations = new ArrayList<>();
            try (PreparedStatement outgoing = connection.prepareStatement("""
                    WITH requested(path) AS (
                        SELECT value FROM json_each(?)
                    )
                    SELECT DISTINCT r.source_ref AS seed_path, r.target_ref
                    FROM requested q
                    JOIN symbol_relations r
                      ON r.project_id = ?
                     AND r.kind = ?
                     AND r.source_ref = q.path
                    ORDER BY r.source_ref, r.target_ref
                    LIMIT ?
                    """)) {
                outgoing.setString(1, requestedPaths);
                outgoing.setString(2, projectId.toString());
                outgoing.setString(3, RelationKind.IMPORTS.name());
                outgoing.setInt(4, maxEdges);
                try (ResultSet resultSet = outgoing.executeQuery()) {
                    while (resultSet.next()) {
                        outgoingRelations.add(new GraphImportTarget(
                                resultSet.getString("seed_path"),
                                resultSet.getString("target_ref")));
                    }
                }
            }

            Map<String, String> typeOwners = findTypeOwners(
                    connection,
                    projectId,
                    collectTypeOwnerCandidates(outgoingRelations));
            for (GraphImportTarget relation : outgoingRelations) {
                if (projectedEdges >= maxEdges) {
                    break;
                }
                String neighborPath = resolveTypeOwner(typeOwners, relation.targetRef());
                if (addGraphNeighbor(neighbors, relation.seedPath(), neighborPath)) {
                    projectedEdges++;
                }
            }

            int remainingEdges = maxEdges - projectedEdges;
            if (remainingEdges > 0) {
                try (PreparedStatement incoming = connection.prepareStatement("""
                        WITH requested(path) AS (
                            SELECT value FROM json_each(?)
                        ),
                        requested_types AS (
                            SELECT s.qualified_name, f.relative_path
                            FROM requested q
                            JOIN indexed_files f
                              ON f.project_id = ? AND f.relative_path = q.path
                            JOIN symbols s ON s.file_id = f.id
                            WHERE s.kind IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM', 'ANNOTATION', 'TYPE')
                        )
                        SELECT DISTINCT rt.relative_path AS seed_path,
                                        r.source_ref AS neighbor_path
                        FROM requested_types rt
                        JOIN symbol_relations r
                          ON r.project_id = ?
                         AND r.kind = ?
                         AND (
                             r.target_ref = rt.qualified_name
                             OR (
                                 r.target_ref >= rt.qualified_name || '.'
                                 AND r.target_ref < rt.qualified_name || '/'
                             )
                         )
                        WHERE r.source_ref <> rt.relative_path
                        ORDER BY rt.relative_path, r.source_ref
                        LIMIT ?
                        """)) {
                    incoming.setString(1, requestedPaths);
                    incoming.setString(2, projectId.toString());
                    incoming.setString(3, projectId.toString());
                    incoming.setString(4, RelationKind.IMPORTS.name());
                    incoming.setInt(5, remainingEdges);
                    try (ResultSet resultSet = incoming.executeQuery()) {
                        while (resultSet.next() && projectedEdges < maxEdges) {
                            if (addGraphNeighbor(
                                    neighbors,
                                    resultSet.getString("seed_path"),
                                    resultSet.getString("neighbor_path"))) {
                                projectedEdges++;
                            }
                        }
                    }
                }
            }

            Map<String, Set<String>> immutable = new LinkedHashMap<>();
            neighbors.forEach((path, values) -> immutable.put(path, Set.copyOf(values)));
            return Map.copyOf(immutable);
        } catch (SQLException exception) {
            throw persistence("Impossible de projeter le voisinage graphe du projet " + projectId, exception);
        }
    }

    static Map<String, String> findTypeOwners(
            Connection connection,
            UUID projectId,
            Set<String> qualifiedNames) throws SQLException {
        if (qualifiedNames.isEmpty()) {
            return Map.of();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH requested(qualified_name) AS (
                    SELECT value FROM json_each(?)
                )
                SELECT s.qualified_name, MIN(f.relative_path) AS relative_path
                FROM requested q
                JOIN symbols s ON s.qualified_name = q.qualified_name
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND s.kind IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM', 'ANNOTATION', 'TYPE')
                GROUP BY s.qualified_name
                ORDER BY s.qualified_name
                """)) {
            statement.setString(1, serializePaths(qualifiedNames.stream().sorted().toList()));
            statement.setString(2, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, String> owners = new LinkedHashMap<>();
                while (resultSet.next()) {
                    owners.put(resultSet.getString(QUALIFIED_NAME_COLUMN), resultSet.getString(RELATIVE_PATH_COLUMN));
                }
                return Map.copyOf(owners);
            }
        }
    }

    static Set<String> collectTypeOwnerCandidates(List<GraphImportTarget> relations) {
        Set<String> candidates = new LinkedHashSet<>();
        for (GraphImportTarget relation : relations) {
            String candidate = relation.targetRef();
            while (candidate != null && !candidate.isBlank()) {
                candidates.add(candidate);
                int separator = candidate.lastIndexOf('.');
                if (separator < 0) {
                    break;
                }
                candidate = candidate.substring(0, separator);
            }
        }
        return candidates;
    }

    static String resolveTypeOwner(Map<String, String> typeOwners, String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            return null;
        }
        String candidate = targetRef;
        while (true) {
            String owner = typeOwners.get(candidate);
            if (owner != null) {
                return owner;
            }
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) {
                return null;
            }
            candidate = candidate.substring(0, separator);
        }
    }

    static boolean addGraphNeighbor(
            Map<String, Set<String>> neighbors,
            String seedPath,
            String neighborPath) {
        if (neighborPath == null || neighborPath.equals(seedPath)) {
            return false;
        }
        return neighbors.computeIfAbsent(seedPath, ignored -> new LinkedHashSet<>()).add(neighborPath);
    }
    private record GraphImportTarget(String seedPath, String targetRef) { }

}
