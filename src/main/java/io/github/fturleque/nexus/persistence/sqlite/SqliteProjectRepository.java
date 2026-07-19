package io.github.fturleque.nexus.persistence.sqlite;

import io.github.fturleque.nexus.persistence.PersistenceException;
import io.github.fturleque.nexus.project.IndexStatus;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.project.ProjectSourceType;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        try (Connection connection = database.openConnection()) {
            boolean initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertProject(connection, project);
                replaceValues(connection, "project_languages", "language", project.id(), project.languages());
                replaceValues(connection, "project_technologies", "technology", project.id(), project.technologies());
                connection.commit();
                return project;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(initialAutoCommit);
            }
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
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM projects ORDER BY name, root_path");
             ResultSet resultSet = statement.executeQuery()) {
            List<ProjectDescriptor> projects = new ArrayList<>();
            while (resultSet.next()) {
                projects.add(mapProject(connection, resultSet));
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

    private static void upsertProject(Connection connection, ProjectDescriptor project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    root_path = excluded.root_path,
                    source_type = excluded.source_type,
                    last_indexed_at = excluded.last_indexed_at,
                    index_status = excluded.index_status
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
            statement.executeUpdate();
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
        UUID projectId = UUID.fromString(resultSet.getString("id"));
        String indexedAt = resultSet.getString("last_indexed_at");
        return new ProjectDescriptor(
                projectId,
                resultSet.getString("name"),
                Path.of(resultSet.getString("root_path")),
                ProjectSourceType.valueOf(resultSet.getString("source_type")),
                loadValues(connection, "project_languages", "language", projectId),
                loadValues(connection, "project_technologies", "technology", projectId),
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
}
