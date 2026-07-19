package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SqliteDatabase {

    private final Path databaseFile;

    public SqliteDatabase(NexusPaths paths) throws SQLException, IOException {
        Objects.requireNonNull(paths, "paths");
        this.databaseFile = paths.databaseFile();
        Path parent = databaseFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Connection connection = openConnection()) {
            SchemaMigrator.migrate(connection);
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    public Path databaseFile() {
        return databaseFile;
    }
}
