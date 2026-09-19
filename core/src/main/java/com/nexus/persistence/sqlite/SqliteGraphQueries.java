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

import static com.nexus.persistence.sqlite.SqliteIndexRows.*;
import static com.nexus.persistence.sqlite.SqliteIndexSql.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteGraphQueries {

    static final String OUTGOING_GRAPH_SQL = """
            WITH requested(path) AS (
                SELECT value FROM json_each(?)
            )
            SELECT DISTINCT r.source_ref AS seed_path, r.target_ref
            FROM requested q
            CROSS JOIN symbol_relations r
            WHERE r.project_id = ? AND r.kind = ? AND r.source_ref = q.path
            ORDER BY r.source_ref, r.target_ref
            LIMIT ?
            """;

    static final String TARGET_TYPE_OWNERS_SQL = """
            WITH requested(qualified_name) AS (
                SELECT value FROM json_each(?)
            )
            SELECT s.qualified_name, MIN(f.relative_path) AS relative_path
            FROM requested q
            CROSS JOIN symbols s
            CROSS JOIN indexed_files f
            WHERE s.qualified_name = q.qualified_name AND f.id = s.file_id
              AND f.project_id = ?
              AND s.kind IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM', 'ANNOTATION', 'TYPE')
            GROUP BY s.qualified_name
            ORDER BY s.qualified_name
            """;

    // Equality and prefix lookups are separated so SQLite can constrain target_ref
    // through idx_symbol_relations_project_kind_target in both branches.
    static final String INCOMING_GRAPH_SQL = """
            WITH requested(path) AS (
                SELECT value FROM json_each(?1)
            ),
            requested_types AS MATERIALIZED (
                SELECT DISTINCT s.qualified_name, f.relative_path
                FROM requested q
                CROSS JOIN indexed_files f
                CROSS JOIN symbols s
                WHERE f.project_id = ?2 AND f.relative_path = q.path
                  AND s.file_id = f.id
                  AND s.kind IN ('CLASS', 'INTERFACE', 'RECORD', 'ENUM', 'ANNOTATION', 'TYPE')
            ),
            incoming(seed_path, neighbor_path) AS (
                SELECT rt.relative_path, r.source_ref
                FROM requested_types rt
                CROSS JOIN symbol_relations r INDEXED BY idx_symbol_relations_project_kind_target
                WHERE r.project_id = ?3 AND r.kind = ?4
                  AND r.target_ref = rt.qualified_name
                  AND r.source_ref <> rt.relative_path
                UNION
                SELECT rt.relative_path, r.source_ref
                FROM requested_types rt
                CROSS JOIN symbol_relations r INDEXED BY idx_symbol_relations_project_kind_target
                WHERE r.project_id = ?3 AND r.kind = ?4
                  AND r.target_ref >= rt.qualified_name || '.'
                  AND r.target_ref < rt.qualified_name || '/'
                  AND r.source_ref <> rt.relative_path
            )
            SELECT seed_path, neighbor_path
            FROM incoming
            ORDER BY seed_path, neighbor_path
            LIMIT ?5
            """;

    private final SqliteDatabase database;

    SqliteGraphQueries(SqliteDatabase database) {
        this.database = database;
    }

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
            try (PreparedStatement outgoing = connection.prepareStatement(OUTGOING_GRAPH_SQL)) {
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
                try (PreparedStatement incoming = connection.prepareStatement(INCOMING_GRAPH_SQL)) {
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
        try (PreparedStatement statement = connection.prepareStatement(TARGET_TYPE_OWNERS_SQL)) {
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

    record GraphImportTarget(String seedPath, String targetRef) {
    }
}
