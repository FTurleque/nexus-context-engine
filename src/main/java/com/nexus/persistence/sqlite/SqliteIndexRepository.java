package com.nexus.persistence.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.ScannedFile;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.persistence.PersistenceException;
import com.nexus.search.ResultLimitPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SqliteIndexRepository implements IndexRepository {

    private static final String EMBEDDED_SOURCE_PROVIDER = CodeSymbol.DEFAULT_SOURCE_PROVIDER;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final SqliteDatabase database;

    public SqliteIndexRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Map<String, IndexedFile> findFiles(UUID projectId) {
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

    @Override
    public Map<String, IndexedFile> findFiles(UUID projectId, Set<String> relativePaths) {
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

    @Override
    public List<IndexedSymbol> findSymbols(UUID projectId) {
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

    @Override
    public Map<String, String> findTypeOwners(UUID projectId) {
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
                    owners.put(resultSet.getString("qualified_name"), resultSet.getString("relative_path"));
                }
                return Map.copyOf(owners);
            }
        } catch (SQLException exception) {
            throw persistence("Impossible de projeter les propriétaires de types du projet " + projectId, exception);
        }
    }

    @Override
    public List<IndexedSymbol> searchSymbols(UUID projectId, String query, int limit) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        ResultLimitPolicy.validateInternalRetrieval(limit);
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String contains = "%" + escapeLike(normalized) + "%";
        String prefix = escapeLike(normalized) + "%";
        String firstCharacter = normalized.substring(0, 1);
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                            s.signature, s.start_line, s.end_line, s.source_provider
                     FROM symbols s
                     JOIN indexed_files f ON f.id = s.file_id
                     WHERE f.project_id = ?
                       AND (
                           LOWER(s.name) LIKE ? ESCAPE '\\'
                           OR LOWER(s.qualified_name) LIKE ? ESCAPE '\\'
                           OR (SUBSTR(LOWER(s.name), 1, 1) = ?
                               AND ABS(LENGTH(s.name) - ?) <= 3)
                       )
                     ORDER BY
                         CASE
                             WHEN LOWER(s.name) = ? THEN 0
                             WHEN LOWER(s.qualified_name) = ? THEN 1
                             WHEN LOWER(s.name) LIKE ? ESCAPE '\\' THEN 2
                             ELSE 3
                         END,
                         s.qualified_name, f.relative_path, s.start_line, s.source_provider
                     LIMIT ?
                     """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, contains);
            statement.setString(3, contains);
            statement.setString(4, firstCharacter);
            statement.setInt(5, normalized.length());
            statement.setString(6, normalized);
            statement.setString(7, normalized);
            statement.setString(8, prefix);
            statement.setInt(9, limit);
            return readSymbols(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de rechercher les symboles du projet " + projectId, exception);
        }
    }

    @Override
    public List<SymbolRelation> findRelations(UUID projectId) {
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

    @Override
    public List<SymbolRelation> findImportRelations(UUID projectId) {
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

    @Override
    public Map<String, Set<String>> findGraphNeighbors(
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

    @Override
    public Set<String> findExternalProviders(UUID projectId) {
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

    @Override
    public List<SymbolRelation> searchRelations(UUID projectId, String symbol, int limit) {
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        ResultLimitPolicy.validate(limit);
        String normalized = symbol.trim().toLowerCase(Locale.ROOT);
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

    @Override
    public void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths) {
        try (Connection connection = database.openConnection()) {
            boolean initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteRemovedFiles(connection, projectId, removedPaths);
                for (IndexedFileUpdate update : updates) {
                    long fileId = upsertFile(connection, projectId, update.file());
                    replaceAnalysis(connection, projectId, fileId, update);
                }
                if (!updates.isEmpty() || !removedPaths.isEmpty()) {
                    bumpGeneration(connection, projectId);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(initialAutoCommit);
            }
        } catch (SQLException exception) {
            throw persistence("Impossible de mettre à jour l'index SQLite du projet " + projectId, exception);
        }
    }

    @Override
    public void replaceExternalCodeIntelligence(UUID projectId, CodeIntelligenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (EMBEDDED_SOURCE_PROVIDER.equals(snapshot.sourceProvider())) {
            throw new IllegalArgumentException("Le provider embarqué ne peut pas être remplacé comme index externe");
        }
        try (Connection connection = database.openConnection()) {
            boolean initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Map<String, Long> fileIds = findFileIds(connection, projectId);
                if (externalSnapshotMatches(connection, projectId, fileIds.keySet(), snapshot)) {
                    connection.commit();
                    return;
                }
                deleteProviderData(connection, projectId, snapshot.sourceProvider());
                insertExternalSymbols(connection, fileIds, snapshot.symbols());
                insertExternalRelations(connection, projectId, fileIds, snapshot.relations());
                bumpGeneration(connection, projectId);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(initialAutoCommit);
            }
        } catch (SQLException exception) {
            throw persistence(
                    "Impossible de remplacer l'intelligence de code du provider " + snapshot.sourceProvider(),
                    exception);
        }
    }

    @Override
    public IndexStatistics statistics(UUID projectId) {
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

    @Override
    public long generation(UUID projectId) {
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

    private static Map<String, IndexedFile> readFiles(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            Map<String, IndexedFile> files = new LinkedHashMap<>();
            while (resultSet.next()) {
                IndexedFile file = new IndexedFile(
                        resultSet.getLong("id"),
                        UUID.fromString(resultSet.getString("project_id")),
                        resultSet.getString("relative_path"),
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

    private static List<IndexedSymbol> readSymbols(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<IndexedSymbol> symbols = new ArrayList<>();
            while (resultSet.next()) {
                symbols.add(new IndexedSymbol(
                        resultSet.getString("relative_path"),
                        new CodeSymbol(
                                SymbolKind.valueOf(resultSet.getString("kind")),
                                resultSet.getString("name"),
                                resultSet.getString("qualified_name"),
                                resultSet.getString("signature"),
                                resultSet.getInt("start_line"),
                                resultSet.getInt("end_line"),
                                resultSet.getString("source_provider"))));
            }
            return List.copyOf(symbols);
        }
    }

    private static List<SymbolRelation> readRelations(PreparedStatement statement) throws SQLException {
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

    private static Map<String, String> findTypeOwners(
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
                    owners.put(resultSet.getString("qualified_name"), resultSet.getString("relative_path"));
                }
                return Map.copyOf(owners);
            }
        }
    }

    private static Set<String> collectTypeOwnerCandidates(List<GraphImportTarget> relations) {
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

    private static String resolveTypeOwner(Map<String, String> typeOwners, String targetRef) {
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

    private static boolean addGraphNeighbor(
            Map<String, Set<String>> neighbors,
            String seedPath,
            String neighborPath) {
        if (neighborPath == null || neighborPath.equals(seedPath)) {
            return false;
        }
        return neighbors.computeIfAbsent(seedPath, ignored -> new LinkedHashSet<>()).add(neighborPath);
    }

    private static void deleteRemovedFiles(Connection connection, UUID projectId, Set<String> removedPaths)
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

    private static long upsertFile(Connection connection, UUID projectId, ScannedFile file) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, file.relativePath());
            statement.setString(3, file.language());
            statement.setLong(4, file.sizeBytes());
            statement.setString(5, file.contentHash());
            statement.setString(6, file.modifiedAt().toString());
            statement.setInt(7, file.estimatedTokens());
            statement.setString(8, file.category().name());
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM indexed_files WHERE project_id = ? AND relative_path = ?")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, file.relativePath());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Fichier indexé introuvable après upsert : " + file.relativePath());
                }
                return resultSet.getLong(1);
            }
        }
    }

    private static void replaceAnalysis(
            Connection connection,
            UUID projectId,
            long fileId,
            IndexedFileUpdate update) throws SQLException {
        try (PreparedStatement deleteRelations = connection.prepareStatement(
                "DELETE FROM symbol_relations WHERE file_id = ? AND source_provider = ?");
             PreparedStatement deleteSymbols = connection.prepareStatement(
                     "DELETE FROM symbols WHERE file_id = ? AND source_provider = ?")) {
            deleteRelations.setLong(1, fileId);
            deleteRelations.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteRelations.executeUpdate();
            deleteSymbols.setLong(1, fileId);
            deleteSymbols.setString(2, EMBEDDED_SOURCE_PROVIDER);
            deleteSymbols.executeUpdate();
        }
        insertSymbols(connection, fileId, update.analysis().symbols());
        insertRelations(connection, projectId, fileId, update.analysis().relations());
    }

    private static void insertSymbols(Connection connection, long fileId, List<CodeSymbol> symbols)
            throws SQLException {
        if (symbols.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbols(
                    file_id, kind, name, qualified_name, signature,
                    start_line, end_line, source_provider)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (CodeSymbol symbol : symbols) {
                statement.setLong(1, fileId);
                statement.setString(2, symbol.kind().name());
                statement.setString(3, symbol.name());
                statement.setString(4, symbol.qualifiedName());
                statement.setString(5, symbol.signature());
                statement.setInt(6, symbol.startLine());
                statement.setInt(7, symbol.endLine());
                statement.setString(8, symbol.sourceProvider());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertRelations(
            Connection connection,
            UUID projectId,
            long fileId,
            List<SymbolRelation> relations) throws SQLException {
        if (relations.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO symbol_relations(
                    project_id, file_id, kind, source_ref, target_ref, confidence, source_provider)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (SymbolRelation relation : relations) {
                statement.setString(1, projectId.toString());
                statement.setLong(2, fileId);
                statement.setString(3, relation.kind().name());
                statement.setString(4, relation.source());
                statement.setString(5, relation.target());
                statement.setDouble(6, relation.confidence());
                statement.setString(7, relation.sourceProvider());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static boolean externalSnapshotMatches(
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

    private static List<IndexedSymbol> readProviderSymbols(
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

    private static List<IndexedRelation> readProviderRelations(
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
                            resultSet.getString("relative_path"),
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

    private static void deleteProviderData(Connection connection, UUID projectId, String sourceProvider)
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

    private static Map<String, Long> findFileIds(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, relative_path FROM indexed_files WHERE project_id = ?")) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, Long> fileIds = new LinkedHashMap<>();
                while (resultSet.next()) {
                    fileIds.put(resultSet.getString("relative_path"), resultSet.getLong("id"));
                }
                return fileIds;
            }
        }
    }

    private static void insertExternalSymbols(
            Connection connection,
            Map<String, Long> fileIds,
            List<IndexedSymbol> symbols) throws SQLException {
        // La persistence est provenance-aware : deux providers indépendants qui
        // décrivent un fait équivalent produisent deux lignes distinctes, une par
        // provider. Le contrôle d'existence porte donc sur source_provider afin de
        // ne dédupliquer qu'à l'intérieur du snapshot du provider courant (défense
        // contre les doublons internes), jamais entre providers.
        try (PreparedStatement exists = connection.prepareStatement("""
                SELECT 1
                FROM symbols
                WHERE file_id = ? AND kind = ? AND name = ? AND start_line = ?
                  AND source_provider = ?
                LIMIT 1
                """)) {
            for (IndexedSymbol indexedSymbol : symbols) {
                Long fileId = fileIds.get(indexedSymbol.relativePath());
                if (fileId == null || symbolExists(exists, fileId, indexedSymbol.symbol())) {
                    continue;
                }
                insertSymbols(connection, fileId, List.of(indexedSymbol.symbol()));
            }
        }
    }

    private static boolean symbolExists(PreparedStatement statement, long fileId, CodeSymbol symbol)
            throws SQLException {
        statement.setLong(1, fileId);
        statement.setString(2, symbol.kind().name());
        statement.setString(3, symbol.name());
        statement.setInt(4, symbol.startLine());
        statement.setString(5, symbol.sourceProvider());
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        }
    }

    private static void insertExternalRelations(
            Connection connection,
            UUID projectId,
            Map<String, Long> fileIds,
            List<IndexedRelation> relations) throws SQLException {
        // Même sémantique provenance-aware que pour les symboles : une relation
        // équivalente fournie par deux providers distincts est conservée en deux
        // lignes, de sorte que la suppression d'un provider ne détruit pas la
        // relation encore fournie par l'autre.
        try (PreparedStatement exists = connection.prepareStatement("""
                SELECT 1
                FROM symbol_relations
                WHERE project_id = ? AND kind = ? AND source_ref = ? AND target_ref = ?
                  AND source_provider = ?
                LIMIT 1
                """)) {
            for (IndexedRelation indexedRelation : relations) {
                Long fileId = fileIds.get(indexedRelation.relativePath());
                if (fileId == null || relationExists(exists, projectId, indexedRelation.relation())) {
                    continue;
                }
                insertRelations(connection, projectId, fileId, List.of(indexedRelation.relation()));
            }
        }
    }

    private static boolean relationExists(
            PreparedStatement statement,
            UUID projectId,
            SymbolRelation relation) throws SQLException {
        statement.setString(1, projectId.toString());
        statement.setString(2, relation.kind().name());
        statement.setString(3, relation.source());
        statement.setString(4, relation.target());
        statement.setString(5, relation.sourceProvider());
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        }
    }

    private static void bumpGeneration(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_index_generations(project_id, generation)
                VALUES (?, 1)
                ON CONFLICT(project_id) DO UPDATE SET generation = generation + 1
                """)) {
            statement.setString(1, projectId.toString());
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String serializePaths(List<String> paths) {
        try {
            return JSON_MAPPER.writeValueAsString(paths);
        } catch (JsonProcessingException exception) {
            throw new PersistenceException("Impossible de sérialiser les chemins de fichiers ciblés", exception);
        }
    }

    private static PersistenceException persistence(String message, SQLException exception) {
        return new PersistenceException(message, exception);
    }

    private record GraphImportTarget(String seedPath, String targetRef) {
    }
}
