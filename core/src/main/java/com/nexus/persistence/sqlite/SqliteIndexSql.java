package com.nexus.persistence.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeSymbol;
import com.nexus.persistence.PersistenceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nexus.persistence.sqlite.SqliteIndexRows.*;

/** Capacité interne de persistance ; partage les sessions et transactions de SqliteDatabase. */
final class SqliteIndexSql {
    static final String EMBEDDED_SOURCE_PROVIDER = CodeSymbol.DEFAULT_SOURCE_PROVIDER;
    static final String QUALIFIED_NAME_COLUMN = "qualified_name";
    static final String RELATIVE_PATH_COLUMN = "relative_path";
    static final int TRIGRAM_MIN_CODE_POINTS = 3;
    static final long FUZZY_SMALL_CANDIDATE_THRESHOLD = 10_000L;
    static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    static void bumpGeneration(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_index_generations(project_id, generation)
                VALUES (?, 1)
                ON CONFLICT(project_id) DO UPDATE SET generation = generation + 1
                """)) {
            statement.setString(1, projectId.toString());
            statement.executeUpdate();
        }
    }

    static long count(Connection connection, String sql, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    static boolean usesTrigramIndex(String normalized) {
        return normalized.codePointCount(0, normalized.length()) >= TRIGRAM_MIN_CODE_POINTS;
    }

    static String firstCodePoint(String value) {
        return value.substring(0, value.offsetByCodePoints(0, 1));
    }

    static String ftsTrigramQuery(String value) {
        int[] codePoints = value.codePoints().toArray();
        LinkedHashSet<String> trigrams = new LinkedHashSet<>();
        for (int index = 0; index <= codePoints.length - TRIGRAM_MIN_CODE_POINTS; index++) {
            trigrams.add(new String(codePoints, index, TRIGRAM_MIN_CODE_POINTS));
        }
        return trigrams.stream()
                .map(trigram -> "\"" + trigram.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(" AND "));
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String serializePaths(List<String> paths) {
        try {
            return JSON_MAPPER.writeValueAsString(paths);
        } catch (JsonProcessingException exception) {
            throw new PersistenceException("Impossible de sérialiser les chemins de fichiers ciblés", exception);
        }
    }

    static PersistenceException persistence(String message, SQLException exception) {
        return new PersistenceException(message, exception);
    }
}
