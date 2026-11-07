package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.persistence.PersistenceException;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** La façade ne laisse pas traverser JDBC : un stockage illisible remonte en {@link PersistenceException}. */
class NexusApplicationPersistenceBoundaryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void unreadableStorageSurfacesAsPersistenceExceptionWithItsJdbcCause() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        Files.createDirectories(paths.home());
        Files.writeString(paths.databaseFile(), "pas une base SQLite ".repeat(64));

        PersistenceException createFailure = assertThrows(PersistenceException.class,
                () -> NexusApplication.create(paths, SemanticSearchConfiguration.disabled()));
        assertInstanceOf(SQLException.class, createFailure.getCause());

        PersistenceException longLivedFailure = assertThrows(PersistenceException.class,
                () -> NexusApplication.createLongLived(paths, SemanticSearchConfiguration.disabled()));
        assertInstanceOf(SQLException.class, longLivedFailure.getCause());
    }
}
