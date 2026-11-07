package com.nexus.persistence.sqlite;

import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedSymbol;
import com.nexus.search.ResultLimitPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * pas des autres projets de la base. Les termes d'une requête sont interrogés en lot : le nombre de requêtes
 * SQL ne dépend pas du nombre de termes, chaque terme étant borné par son propre {@code LIMIT} dans une
 * requête composée.</p>
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

    private static final String SYMBOL_COLUMNS = """
            f.relative_path AS relative_path, s.kind AS kind, s.name AS name,
                           s.qualified_name AS qualified_name, s.signature AS signature,
                           s.start_line AS start_line, s.end_line AS end_line,
                           s.source_provider AS source_provider""";

    /**
     * Bras d'une requête composée : chaque terme est borné par son propre {@code LIMIT}, ce qui laisse SQLite
     * s'arrêter tôt par terme. Paramètres : terme, motif FTS, projet, contains, contains, normalisé,
     * normalisé, préfixe, limite.
     */
    static final String TRIGRAM_ARM_SQL = """
            SELECT * FROM (
                SELECT ? AS term_index, %s
                FROM symbol_search_fts search
                JOIN symbols s ON s.id = search.rowid
                JOIN indexed_files f ON f.id = s.file_id
                WHERE symbol_search_fts MATCH ?
                  AND f.project_id = ?
                  AND (LOWER(s.name) LIKE ? ESCAPE '\\'
                       OR LOWER(s.qualified_name) LIKE ? ESCAPE '\\')
                ORDER BY
                    CASE
                        WHEN LOWER(s.name) = ? THEN 0
                        WHEN LOWER(s.qualified_name) = ? THEN 1
                        WHEN LOWER(s.name) LIKE ? ESCAPE '\\' THEN 2
                        ELSE 3
                    END,
                    s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?)
            """.formatted(SYMBOL_COLUMNS);

    /**
     * Préfiltre approché quand peu de candidats existent : parcours de l'index scopé au projet.
     * Paramètres : terme, projet, premier caractère, longueur minimale, longueur maximale, limite.
     */
    static final String FUZZY_BATCH_SQL = """
            SELECT * FROM (
                SELECT ? AS term_index, %s
                FROM symbols s INDEXED BY idx_symbols_file_fuzzy_prefilter
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?)
            """.formatted(SYMBOL_COLUMNS);

    /**
     * Préfiltre approché quand de nombreux candidats existent : parcours dans l'ordre de
     * {@code idx_symbols_qualified_name}, qui s'arrête dès que la limite est atteinte. Mêmes paramètres
     * que {@link #FUZZY_BATCH_SQL}.
     */
    static final String FUZZY_ORDERED_ARM_SQL = """
            SELECT * FROM (
                SELECT ? AS term_index, %s
                FROM symbols s INDEXED BY idx_symbols_qualified_name
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?)
            """.formatted(SYMBOL_COLUMNS);

    /** Nombre de candidats du préfiltre, plafonné ; mêmes paramètres que {@link #FUZZY_BATCH_SQL}. */
    static final String FUZZY_COUNT_ARM_SQL = """
            SELECT ? AS term_index, COUNT(*) AS candidate_count FROM (
                SELECT 1
                FROM symbols s INDEXED BY idx_symbols_file_fuzzy_prefilter
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                LIMIT ?)
            """;

    /**
     * Requêtes de moins de trois points de code. Paramètres : terme, projet, contains, contains,
     * premier caractère, longueur, normalisé, normalisé, préfixe, limite.
     */
    static final String LIKE_ARM_SQL = """
            SELECT * FROM (
                SELECT ? AS term_index, %s
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
                LIMIT ?)
            """.formatted(SYMBOL_COLUMNS);

    /** Plein texte trigram : au plus {@code limit} symboles par requête, requêtes d'au moins trois points de code. */
    static Map<String, List<IndexedSymbol>> searchSymbolsWithTrigram(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        List<String> arms = new ArrayList<>();
        List<Object[]> parameters = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = queries.get(index);
            String contains = "%" + escapeLike(normalized) + "%";
            arms.add(TRIGRAM_ARM_SQL);
            parameters.add(new Object[] {index, ftsTrigramQuery(normalized), projectId.toString(), contains,
                    contains, normalized, normalized, escapeLike(normalized) + "%", limit});
        }
        return runArms(connection, arms, parameters, queries);
    }

    /**
     * Préfiltre approché (premier caractère, longueur à plus ou moins trois), scopé aux fichiers du projet.
     * Une première requête compte les candidats de chaque terme (plafonné au seuil) pour choisir, par terme,
     * le parcours de l'index scopé ou le parcours ordonné qui s'arrête à la limite.
     */
    static Map<String, List<IndexedSymbol>> searchFuzzySymbols(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        List<Object[]> fuzzyParameters = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = queries.get(index);
            int queryLength = normalized.codePointCount(0, normalized.length());
            fuzzyParameters.add(new Object[] {index, projectId.toString(), firstCodePoint(normalized),
                    Math.max(0, queryLength - 3), queryLength + 3, limit});
        }
        List<Object[]> countParameters = new ArrayList<>();
        for (Object[] row : fuzzyParameters) {
            Object[] counted = row.clone();
            counted[5] = FUZZY_SMALL_CANDIDATE_THRESHOLD + 1L;
            countParameters.add(counted);
        }
        Map<Integer, Long> counts = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                String.join(" UNION ALL ", Collections.nCopies(countParameters.size(), FUZZY_COUNT_ARM_SQL)))) {
            bind(statement, countParameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    counts.put(resultSet.getInt("term_index"), resultSet.getLong("candidate_count"));
                }
            }
        }
        List<String> arms = new ArrayList<>();
        List<Object[]> parameters = new ArrayList<>();
        for (Object[] row : fuzzyParameters) {
            long count = counts.getOrDefault((Integer) row[0], 0L);
            if (count == 0L) {
                continue;
            }
            arms.add(count <= FUZZY_SMALL_CANDIDATE_THRESHOLD ? FUZZY_BATCH_SQL : FUZZY_ORDERED_ARM_SQL);
            parameters.add(row);
        }
        return arms.isEmpty() ? Map.of() : runArms(connection, arms, parameters, queries);
    }

    /** Requêtes de moins de trois points de code : repli LIKE historique. */
    static Map<String, List<IndexedSymbol>> searchSymbolsWithLike(
            Connection connection, UUID projectId, List<String> queries, int limit) throws SQLException {
        List<String> arms = new ArrayList<>();
        List<Object[]> parameters = new ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String normalized = queries.get(index);
            String contains = "%" + escapeLike(normalized) + "%";
            arms.add(LIKE_ARM_SQL);
            parameters.add(new Object[] {index, projectId.toString(), contains, contains,
                    firstCodePoint(normalized), normalized.codePointCount(0, normalized.length()),
                    normalized, normalized, escapeLike(normalized) + "%", limit});
        }
        return runArms(connection, arms, parameters, queries);
    }

    private static Map<String, List<IndexedSymbol>> runArms(
            Connection connection,
            List<String> arms,
            List<Object[]> parameters,
            List<String> queries) throws SQLException {
        Map<String, List<IndexedSymbol>> byQuery = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(String.join(" UNION ALL ", arms))) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String query = queries.get(resultSet.getInt("term_index"));
                    byQuery.computeIfAbsent(query, ignored -> new ArrayList<>()).add(readSymbol(resultSet));
                }
            }
        }
        return byQuery;
    }

    private static void bind(PreparedStatement statement, List<Object[]> parameters) throws SQLException {
        int position = 1;
        for (Object[] row : parameters) {
            for (Object value : row) {
                statement.setObject(position++, value);
            }
        }
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
