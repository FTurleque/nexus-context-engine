package com.nexus.persistence.sqlite;

import com.nexus.persistence.PersistenceException;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRepository;
import com.nexus.project.ProjectSourceType;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SqliteProjectRepository implements ProjectRepository {

    private final SqliteDatabase database;

    public SqliteProjectRepository(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public ProjectDescriptor save(ProjectDescriptor project) {
        try {
            return database.writeTransaction("save project " + project.id(), connection -> {
                int affectedRows = upsertProject(connection, project);
                if (affectedRows == 0) {
                    return findByRootPath(connection, project.rootPath())
                            .orElseThrow(() -> new SQLException(
                                    "Projet concurrent introuvable après conflit root_path : "
                                            + project.rootPath()));
                }
                replaceValues(connection, "project_languages", "language", project.id(), project.languages());
                replaceValues(connection, "project_technologies", "technology", project.id(), project.technologies());
                return project;
            });
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible d'enregistrer le projet " + project.id(), exception);
        }
    }

    @Override
    public Optional<ProjectDescriptor> findById(UUID projectId) {
        return findOne("SELECT * FROM projects WHERE id = ?", projectId.toString());
    }

    @Override
    public Optional<ProjectDescriptor> findByRootPath(Path rootPath) {
        String normalizedPath = rootPath.toAbsolutePath().normalize().toString();
        return findOne("SELECT * FROM projects WHERE root_path = ?", normalizedPath);
    }

    @Override
    public List<ProjectDescriptor> findAll() {
        try (Connection connection = database.openConnection()) {
            List<ProjectRow> rows = loadProjectRows(connection);
            if (rows.isEmpty()) {
                return List.of();
            }

            Map<UUID, Set<String>> languages = loadAllValues(
                    connection, "project_languages", "language");
            Map<UUID, Set<String>> technologies = loadAllValues(
                    connection, "project_technologies", "technology");

            List<ProjectDescriptor> projects = new ArrayList<>(rows.size());
            for (ProjectRow row : rows) {
                projects.add(row.toDescriptor(
                        languages.getOrDefault(row.id(), Set.of()),
                        technologies.getOrDefault(row.id(), Set.of())));
            }
            return List.copyOf(projects);
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de lire le registre des projets", exception);
        }
    }

    private Optional<ProjectDescriptor> findOne(String sql, String value) {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapProject(connection, resultSet));
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Impossible de lire le registre des projets", exception);
        }
    }

    private static Optional<ProjectDescriptor> findByRootPath(Connection connection, Path rootPath)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM projects WHERE root_path = ?")) {
            statement.setString(1, rootPath.toAbsolutePath().normalize().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapProject(connection, resultSet));
            }
        }
    }

    private static int upsertProject(Connection connection, ProjectDescriptor project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    root_path = excluded.root_path,
                    source_type = excluded.source_type,
                    last_indexed_at = excluded.last_indexed_at,
                    index_status = excluded.index_status
                ON CONFLICT(root_path) DO NOTHING
                """)) {
            statement.setString(1, project.id().toString());
            statement.setString(2, project.name());
            statement.setString(3, project.rootPath().toAbsolutePath().normalize().toString());
            statement.setString(4, project.sourceType().name());
            if (project.lastIndexedAt() == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, project.lastIndexedAt().toString());
            }
            statement.setString(6, project.indexStatus().name());
            return statement.executeUpdate();
        }
    }

    private static void replaceValues(
            Connection connection,
            String table,
            String valueColumn,
            UUID projectId,
            Set<String> values) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE project_id = ?")) {
            delete.setString(1, projectId.toString());
            delete.executeUpdate();
        }
        if (values.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + "(project_id, " + valueColumn + ") VALUES (?, ?)")) {
            for (String value : values) {
                insert.setString(1, projectId.toString());
                insert.setString(2, value);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static ProjectDescriptor mapProject(Connection connection, ResultSet resultSet) throws SQLException {
        ProjectRow row = projectRow(resultSet);
        return row.toDescriptor(
                loadValues(connection, "project_languages", "language", row.id()),
                loadValues(connection, "project_technologies", "technology", row.id()));
    }

    private static List<ProjectRow> loadProjectRows(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM projects ORDER BY name, root_path");
             ResultSet resultSet = statement.executeQuery()) {
            List<ProjectRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(projectRow(resultSet));
            }
            return List.copyOf(rows);
        }
    }

    private static ProjectRow projectRow(ResultSet resultSet) throws SQLException {
        String indexedAt = resultSet.getString("last_indexed_at");
        return new ProjectRow(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("name"),
                Path.of(resultSet.getString("root_path")),
                ProjectSourceType.valueOf(resultSet.getString("source_type")),
                indexedAt == null ? null : Instant.parse(indexedAt),
                IndexStatus.valueOf(resultSet.getString("index_status")));
    }

    private static Set<String> loadValues(
            Connection connection,
            String table,
            String valueColumn,
            UUID projectId) throws SQLException {
        String sql = "SELECT " + valueColumn + " FROM " + table + " WHERE project_id = ? ORDER BY " + valueColumn;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> values = new LinkedHashSet<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return Set.copyOf(values);
            }
        }
    }

    private static Map<UUID, Set<String>> loadAllValues(
            Connection connection,
            String table,
            String valueColumn) throws SQLException {
        String sql = "SELECT project_id, " + valueColumn + " FROM " + table
                + " ORDER BY project_id, " + valueColumn;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            Map<UUID, LinkedHashSet<String>> mutable = new LinkedHashMap<>();
            while (resultSet.next()) {
                UUID projectId = UUID.fromString(resultSet.getString("project_id"));
                mutable.computeIfAbsent(projectId, ignored -> new LinkedHashSet<>())
                        .add(resultSet.getString(valueColumn));
            }
            Map<UUID, Set<String>> immutable = new LinkedHashMap<>();
            mutable.forEach((projectId, values) -> immutable.put(projectId, Set.copyOf(values)));
            return Map.copyOf(immutable);
        }
    }

    private record ProjectRow(
            UUID id,
            String name,
            Path rootPath,
            ProjectSourceType sourceType,
            Instant lastIndexedAt,
            IndexStatus indexStatus) {

        ProjectDescriptor toDescriptor(Set<String> languages, Set<String> technologies) {
            return new ProjectDescriptor(
                    id,
                    name,
                    rootPath,
                    sourceType,
                    languages,
                    technologies,
                    lastIndexedAt,
                    indexStatus);
        }
    }
}
