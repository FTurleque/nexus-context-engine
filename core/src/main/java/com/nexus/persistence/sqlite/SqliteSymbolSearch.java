package com.nexus.persistence.sqlite;

import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedSymbol;
import com.nexus.search.ResultLimitPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.*;
import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteSymbolSearch {
    private final SqliteDatabase database;
    SqliteSymbolSearch(SqliteDatabase database) { this.database = database; }

    List<IndexedSymbol> searchSymbols(UUID projectId, String query, int limit) {
        Objects.requireNonNull(query, "query");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        ResultLimitPolicy.validateInternalRetrieval(limit);
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (!usesTrigramIndex(normalized)) {
            return searchSymbolsWithLike(projectId, normalized, limit);
        }

        // Both candidate queries belong to the same retrieval operation. Reuse
        // its connection so the fuzzy query does not reopen and reload SQLite's schema.
        try (Connection connection = database.openConnection()) {
            List<IndexedSymbol> trigramCandidates = searchSymbolsWithTrigram(connection, projectId, normalized, limit);
            List<IndexedSymbol> fuzzyCandidates = searchFuzzySymbols(connection, projectId, normalized, limit);
            return mergeSymbolCandidates(normalized, trigramCandidates, fuzzyCandidates, limit);
        } catch (SQLException exception) {
            throw persistence("Impossible de rechercher les symboles du projet " + projectId, exception);
        }
    }

    static List<IndexedSymbol> searchSymbolsWithTrigram(
            Connection connection, UUID projectId, String normalized, int limit) throws SQLException {
        String contains = "%" + escapeLike(normalized) + "%";
        String prefix = escapeLike(normalized) + "%";
        try (PreparedStatement statement = connection.prepareStatement("""
                     SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                            s.signature, s.start_line, s.end_line, s.source_provider
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
                     LIMIT ?
                     """)) {
            statement.setString(1, ftsTrigramQuery(normalized));
            statement.setString(2, projectId.toString());
            statement.setString(3, contains);
            statement.setString(4, contains);
            statement.setString(5, normalized);
            statement.setString(6, normalized);
            statement.setString(7, prefix);
            statement.setInt(8, limit);
            return readSymbols(statement);
        }
    }

    static List<IndexedSymbol> searchFuzzySymbols(
            Connection connection, UUID projectId, String normalized, int limit) throws SQLException {
        String firstCharacter = firstCodePoint(normalized);
        int queryLength = normalized.codePointCount(0, normalized.length());
        int minimumLength = Math.max(0, queryLength - 3);
        int maximumLength = queryLength + 3;
        long candidateCount = countFuzzyCandidates(
                connection,
                projectId,
                firstCharacter,
                minimumLength,
                maximumLength);
        if (candidateCount == 0L) {
            return List.of();
        }
        if (candidateCount <= FUZZY_SMALL_CANDIDATE_THRESHOLD) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                           s.signature, s.start_line, s.end_line, s.source_provider
                    FROM symbols s INDEXED BY idx_symbols_fuzzy_prefilter
                    JOIN indexed_files f ON f.id = s.file_id
                    WHERE f.project_id = ?
                      AND SUBSTR(LOWER(s.name), 1, 1) = ?
                      AND LENGTH(s.name) BETWEEN ? AND ?
                    ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                    LIMIT ?
                    """)) {
                return bindAndReadFuzzyCandidates(
                        statement,
                        projectId,
                        firstCharacter,
                        minimumLength,
                        maximumLength,
                        limit);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.relative_path, s.kind, s.name, s.qualified_name,
                       s.signature, s.start_line, s.end_line, s.source_provider
                FROM symbols s INDEXED BY idx_symbols_qualified_name
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                ORDER BY s.qualified_name, f.relative_path, s.start_line, s.source_provider
                LIMIT ?
                """)) {
            return bindAndReadFuzzyCandidates(
                    statement,
                    projectId,
                    firstCharacter,
                    minimumLength,
                    maximumLength,
                    limit);
        }
    }

    static List<IndexedSymbol> bindAndReadFuzzyCandidates(
            PreparedStatement statement,
            UUID projectId,
            String firstCharacter,
            int minimumLength,
            int maximumLength,
            int limit) throws SQLException {
        statement.setString(1, projectId.toString());
        statement.setString(2, firstCharacter);
        statement.setInt(3, minimumLength);
        statement.setInt(4, maximumLength);
        statement.setInt(5, limit);
        return readSymbols(statement);
    }

    static long countFuzzyCandidates(
            Connection connection,
            UUID projectId,
            String firstCharacter,
            int minimumLength,
            int maximumLength) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM symbols s INDEXED BY idx_symbols_fuzzy_prefilter
                JOIN indexed_files f ON f.id = s.file_id
                WHERE f.project_id = ?
                  AND SUBSTR(LOWER(s.name), 1, 1) = ?
                  AND LENGTH(s.name) BETWEEN ? AND ?
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, firstCharacter);
            statement.setInt(3, minimumLength);
            statement.setInt(4, maximumLength);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
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

    private List<IndexedSymbol> searchSymbolsWithLike(UUID projectId, String normalized, int limit) {
        String contains = "%" + escapeLike(normalized) + "%";
        String prefix = escapeLike(normalized) + "%";
        String firstCharacter = firstCodePoint(normalized);
        int queryLength = normalized.codePointCount(0, normalized.length());
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
            statement.setInt(5, queryLength);
            statement.setString(6, normalized);
            statement.setString(7, normalized);
            statement.setString(8, prefix);
            statement.setInt(9, limit);
            return readSymbols(statement);
        } catch (SQLException exception) {
            throw persistence("Impossible de rechercher les symboles du projet " + projectId, exception);
        }
    }
}
