package com.nexus.persistence.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedSymbol;
import com.nexus.persistence.PersistenceException;
import com.nexus.search.ResultLimitPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;
import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/**
 * Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase.
 *
 * <p>La recherche de symboles est scopée au projet ciblé (AUD-PRF-09) : le préfiltre approché part des
 * fichiers du projet puis sonde {@code idx_symbols_file_fuzzy_prefilter} (V015), donc son coût ne dépend
 * pas des autres projets de la base. Les termes d'une requête sont interrogés en lot : au plus une requête
 * SQL par famille (plein texte, préfiltre approché, LIKE) quel que soit le nombre de termes.</p>
 */
final class SqliteSymbolSearch {
    /** Marge sur les 8 termes de SymbolSearchStrategy plus la requête entière. */
    static final int MAX_QUERIES_PER_BATCH = 16;

    private final SqliteDatabase database;
    SqliteSymbolSearch(SqliteDatabase database) { this.database = database; }

    List<IndexedSymbol> searchSymbols(UUID projectId, String query, int limit) {
        Objects.requireNonNull(query, "query");
        return searchSymbols(projectId, List.of(query), limit);
    }

    /**
     * Résultat de chaque requête, dans l'ordre des requêtes distinctes, chacune limitée à {@code limit} :
     * identique à la concaténation de {@link #searchSymbols(UUID, String, int)} par requête.
     */
    List<IndexedSymbol> searchSymbols(UUID projectId, List<String> queries, int limit) {
        Objects.requireNonNull(projectId, "projectId");
        LinkedHashSet<String> normalizedQueries = normalize(queries, limit);
        if (normalizedQueries.isEmpty()) {
            return List.of();
        }
        // Toutes les requêtes du lot appartiennent à la même opération de lecture : une seule connexion.
        try (Connection connection = database.openConnection()) {
            return searchNormalized(connection, projectId, normalizedQueries, limit);
        } catch (SQLException exception) {
            throw persistence("Impossible de rechercher les symboles du projet " + projectId, exception);
        }
    }

    /** Variante sur une connexion fournie par l'appelant, pour mesurer les requêtes SQL émises. */
    static List<IndexedSymbol> searchSymbols(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        Objects.requireNonNull(projectId, "projectId");
        LinkedHashSet<String> normalizedQueries = normalize(queries, limit);
        return normalizedQueries.isEmpty()
                ? List.of() : searchNormalized(connection, projectId, normalizedQueries, limit);
    }

    private static LinkedHashSet<String> normalize(List<String> queries, int limit) {
        Objects.requireNonNull(queries, "queries");
        ResultLimitPolicy.validateInternalRetrieval(limit);
        LinkedHashSet<String> normalizedQueries = new LinkedHashSet<>();
        for (String query : queries) {
            Objects.requireNonNull(query, "query");
            if (query.isBlank()) {
                throw new IllegalArgumentException("query must not be blank");
            }
            normalizedQueries.add(query.trim().toLowerCase(Locale.ROOT));
        }
        if (normalizedQueries.size() > MAX_QUERIES_PER_BATCH) {
            throw new IllegalArgumentException(
                    "Une recherche de symboles par lot accepte au plus " + MAX_QUERIES_PER_BATCH + " requêtes");
        }
        return normalizedQueries;
    }

    private static List<IndexedSymbol> searchNormalized(
            Connection connection,
            UUID projectId,
            LinkedHashSet<String> normalizedQueries,
            int limit) throws SQLException {
        List<String> trigramQueries = normalizedQueries.stream().filter(SqliteIndexSql::usesTrigramIndex).toList();
        List<String> likeQueries = normalizedQueries.stream().filter(query -> !usesTrigramIndex(query)).toList();
        Map<String, List<IndexedSymbol>> trigramRows = trigramQueries.isEmpty()
                ? Map.of() : searchSymbolsWithTrigram(connection, projectId, trigramQueries, limit);
        Map<String, List<IndexedSymbol>> fuzzyRows = trigramQueries.isEmpty()
                ? Map.of() : searchFuzzySymbols(connection, projectId, trigramQueries, limit);
        Map<String, List<IndexedSymbol>> likeRows = likeQueries.isEmpty()
                ? Map.of() : searchSymbolsWithLike(connection, projectId, likeQueries, limit);
        List<IndexedSymbol> result = new ArrayList<>();
        for (String normalized : normalizedQueries) {
            if (usesTrigramIndex(normalized)) {
                result.addAll(mergeSymbolCandidates(
                        normalized,
                        trigramRows.getOrDefault(normalized, List.of()),
                        fuzzyRows.getOrDefault(normalized, List.of()),
                        limit));
            } else {
                result.addAll(likeRows.getOrDefault(normalized, List.of()));
            }
        }
        return List.copyOf(result);
    }

    static final String TRIGRAM_BATCH_SQL = """
            WITH q AS (
                SELECT CAST(json_extract(value, '$.i') AS INTEGER) AS term_index,
                       json_extract(value, '$.match') AS match_query,
                       json_extract(value, '$.normalized') AS normalized,
                       json_extract(value, '$.contains') AS contains_pattern,
                       json_extract(value, '$.prefix') AS prefix_pattern
                FROM json_each(?)
            ), candidates AS (
                SELECT q.term_index AS term_index,
                       f.relative_path AS relative_path, s.kind AS kind, s.name AS name,
                       s.qualified_name AS qualified_name, s.signature AS signature,
                       s.start_line AS start_line, s.end_line AS end_line,
                       s.source_provider AS source_provider,
                       ROW_NUMBER() OVER (
                           PARTITION BY q.term_index
                           ORDER BY
                               CASE
                                   WHEN LOWER(s.name) = q.normalized THEN 0
                                   WHEN LOWER(s.qualified_name) = q.normalized THEN 1
                                   WHEN LOWER(s.name) LIKE q.prefix_pattern ESCAPE '\\' THEN 2
                                   ELSE 3
                               END,
                               s.qualified_name, f.relative_path, s.start_line, s.source_provider) AS rank_in_term
                FROM q
                JOIN symbol_search_fts search ON symbol_search_fts MATCH q.match_query
                JOIN symbols s ON s.id = search.rowid
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND (LOWER(s.name) LIKE q.contains_pattern ESCAPE '\\'
                       OR LOWER(s.qualified_name) LIKE q.contains_pattern ESCAPE '\\')
            )
            SELECT term_index, relative_path, kind, name, qualified_name, signature,
                   start_line, end_line, source_provider
            FROM candidates
            WHERE rank_in_term <= ?
            ORDER BY term_index, rank_in_term
            """;

    static final String FUZZY_BATCH_SQL = """
            WITH q AS (
                SELECT CAST(json_extract(value, '$.i') AS INTEGER) AS term_index,
                       json_extract(value, '$.first') AS first_character,
                       CAST(json_extract(value, '$.min') AS INTEGER) AS minimum_length,
                       CAST(json_extract(value, '$.max') AS INTEGER) AS maximum_length
                FROM json_each(?)
            ), candidates AS (
                SELECT q.term_index AS term_index,
                       f.relative_path AS relative_path, s.kind AS kind, s.name AS name,
                       s.qualified_name AS qualified_name, s.signature AS signature,
                       s.start_line AS start_line, s.end_line AS end_line,
                       s.source_provider AS source_provider,
                       ROW_NUMBER() OVER (
                           PARTITION BY q.term_index
                           ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                       ) AS rank_in_term
                FROM indexed_files f
                JOIN symbols s INDEXED BY idx_symbols_file_fuzzy_prefilter ON s.file_id = f.id
                JOIN q ON SUBSTR(LOWER(s.name), 1, 1) = q.first_character
                      AND LENGTH(s.name) BETWEEN q.minimum_length AND q.maximum_length
                WHERE f.project_id = ?
            )
            SELECT term_index, relative_path, kind, name, qualified_name, signature,
                   start_line, end_line, source_provider
            FROM candidates
            WHERE rank_in_term <= ?
            ORDER BY term_index, rank_in_term
            """;

    static final String LIKE_BATCH_SQL = """
            WITH q AS (
                SELECT CAST(json_extract(value, '$.i') AS INTEGER) AS term_index,
                       json_extract(value, '$.normalized') AS normalized,
                       json_extract(value, '$.contains') AS contains_pattern,
                       json_extract(value, '$.prefix') AS prefix_pattern,
                       json_extract(value, '$.first') AS first_character,
                       CAST(json_extract(value, '$.length') AS INTEGER) AS query_length
                FROM json_each(?)
            ), candidates AS (
                SELECT q.term_index AS term_index,
                       f.relative_path AS relative_path, s.kind AS kind, s.name AS name,
                       s.qualified_name AS qualified_name, s.signature AS signature,
                       s.start_line AS start_line, s.end_line AS end_line,
                       s.source_provider AS source_provider,
                       ROW_NUMBER() OVER (
                           PARTITION BY q.term_index
                           ORDER BY
                               CASE
                                   WHEN LOWER(s.name) = q.normalized THEN 0
                                   WHEN LOWER(s.qualified_name) = q.normalized THEN 1
                                   WHEN LOWER(s.name) LIKE q.prefix_pattern ESCAPE '\\' THEN 2
                                   ELSE 3
                               END,
                               s.qualified_name, f.relative_path, s.start_line, s.source_provider) AS rank_in_term
                FROM indexed_files f
                JOIN symbols s ON s.file_id = f.id
                JOIN q ON (LOWER(s.name) LIKE q.contains_pattern ESCAPE '\\'
                           OR LOWER(s.qualified_name) LIKE q.contains_pattern ESCAPE '\\'
                           OR (SUBSTR(LOWER(s.name), 1, 1) = q.first_character
                               AND ABS(LENGTH(s.name) - q.query_length) <= 3))
                WHERE f.project_id = ?
            )
            SELECT term_index, relative_path, kind, name, qualified_name, signature,
                   start_line, end_line, source_provider
            FROM candidates
            WHERE rank_in_term <= ?
            ORDER BY term_index, rank_in_term
            """;

    /** Plein texte trigram : au plus {@code limit} symboles par requête, requêtes d'au moins trois points de code. */
    static Map<String, List<IndexedSymbol>> searchSymbolsWithTrigram(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = queries.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("i", index);
            row.put("match", ftsTrigramQuery(normalized));
            row.put("normalized", normalized);
            row.put("contains", "%" + escapeLike(normalized) + "%");
            row.put("prefix", escapeLike(normalized) + "%");
            rows.add(row);
        }
        return runBatch(connection, TRIGRAM_BATCH_SQL, rows, projectId, limit, queries);
    }

    /** Préfiltre approché (premier caractère, longueur à plus ou moins trois), scopé aux fichiers du projet. */
    static Map<String, List<IndexedSymbol>> searchFuzzySymbols(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = queries.get(index);
            int queryLength = normalized.codePointCount(0, normalized.length());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("i", index);
            row.put("first", firstCodePoint(normalized));
            row.put("min", Math.max(0, queryLength - 3));
            row.put("max", queryLength + 3);
            rows.add(row);
        }
        return runBatch(connection, FUZZY_BATCH_SQL, rows, projectId, limit, queries);
    }

    /** Requêtes de moins de trois points de code : repli LIKE historique. */
    static Map<String, List<IndexedSymbol>> searchSymbolsWithLike(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = queries.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("i", index);
            row.put("normalized", normalized);
            row.put("contains", "%" + escapeLike(normalized) + "%");
            row.put("prefix", escapeLike(normalized) + "%");
            row.put("first", firstCodePoint(normalized));
            row.put("length", normalized.codePointCount(0, normalized.length()));
            rows.add(row);
        }
        return runBatch(connection, LIKE_BATCH_SQL, rows, projectId, limit, queries);
    }

    private static Map<String, List<IndexedSymbol>> runBatch(
            Connection connection,
            String sql,
            List<Map<String, Object>> queryRows,
            UUID projectId,
            int limit,
            List<String> queries) throws SQLException {
        final String json;
        try {
            json = JSON_MAPPER.writeValueAsString(queryRows);
        } catch (JsonProcessingException exception) {
            throw new PersistenceException("Impossible de sérialiser les requêtes de symboles", exception);
        }
        Map<String, List<IndexedSymbol>> byQuery = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, json);
            statement.setString(2, projectId.toString());
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String query = queries.get(resultSet.getInt("term_index"));
                    byQuery.computeIfAbsent(query, ignored -> new ArrayList<>()).add(readSymbol(resultSet));
                }
            }
        }
        return byQuery;
    }

    static List<IndexedSymbol> mergeSymbolCandidates(
            String normalized,
            List<IndexedSymbol> trigramCandidates,
            List<IndexedSymbol> fuzzyCandidates,
            int limit) {
        LinkedHashSet<IndexedSymbol> unique = new LinkedHashSet<>(trigramCandidates);
        unique.addAll(fuzzyCandidates);
        Comparator<IndexedSymbol> ordering = Comparator
                .comparingInt((IndexedSymbol symbol) -> symbolSearchRank(symbol, normalized))
                .thenComparing(symbol -> symbol.symbol().qualifiedName())
                .thenComparing(IndexedSymbol::relativePath)
                .thenComparingInt(symbol -> symbol.symbol().startLine())
                .thenComparing(symbol -> symbol.symbol().sourceProvider());
        return unique.stream().sorted(ordering).limit(limit).toList();
    }

    static int symbolSearchRank(IndexedSymbol indexedSymbol, String normalized) {
        CodeSymbol symbol = indexedSymbol.symbol();
        String name = symbol.name().toLowerCase(Locale.ROOT);
        String qualifiedName = symbol.qualifiedName().toLowerCase(Locale.ROOT);
        if (name.equals(normalized)) {
            return 0;
        }
        if (qualifiedName.equals(normalized)) {
            return 1;
        }
        if (name.startsWith(normalized)) {
            return 2;
        }
        return 3;
    }
}
