package com.nexus.persistence.sqlite;

import com.nexus.index.IndexWriteSession;
import com.nexus.index.IndexedFileUpdate;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.nexus.persistence.sqlite.SqliteIndexSql.persistence;

/**
 * Écritures canoniques d'une indexation : mise en attente par courtes transactions, publication
 * (faits canoniques et génération) en une transaction. Chaque transaction passe par
 * {@link SqliteDatabase#writeTransaction}, donc par la politique de reprise sur contention.
 */
final class SqliteIndexWriteSession implements IndexWriteSession {
    private static final System.Logger LOGGER = System.getLogger(SqliteIndexWriteSession.class.getName());

    private final SqliteDatabase database;
    private final UUID projectId;
    private boolean finished;

    SqliteIndexWriteSession(SqliteDatabase database, UUID projectId) {
        this.database = Objects.requireNonNull(database, "database");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        // Une indexation interrompue (crash) peut avoir laissé des analyses en attente.
        discardStaged("Impossible d'écarter les analyses en attente du projet " + projectId);
    }

    @Override
    public void stage(List<IndexedFileUpdate> updates) {
        Objects.requireNonNull(updates, "updates");
        ensureActive();
        if (updates.isEmpty()) {
            return;
        }
        try {
            database.writeTransaction("stage index analyses for project " + projectId,
                    connection -> {
                        SqliteIndexStaging.stage(connection, projectId, updates);
                    });
        } catch (SQLException exception) {
            throw persistence("Impossible de mettre en attente les analyses du projet " + projectId, exception);
        }
    }

    @Override
    public void publish(Set<String> removedPaths) {
        Objects.requireNonNull(removedPaths, "removedPaths");
        ensureActive();
        try {
            database.writeTransaction("publish staged index changes for project " + projectId,
                    connection -> {
                        SqliteFileWriter.writeStagedChanges(connection, projectId, removedPaths);
                    });
        } catch (SQLException exception) {
            throw persistence("Impossible de publier l'index SQLite du projet " + projectId, exception);
        }
        finished = true;
    }

    @Override
    public void close() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            discardStaged("Impossible d'écarter les analyses en attente du projet " + projectId);
        } catch (RuntimeException failure) {
            // Les analyses restantes sont écartées à la prochaine ouverture ; ne pas masquer l'échec d'origine.
            LOGGER.log(System.Logger.Level.WARNING, failure.getMessage(), failure);
        }
    }

    private void discardStaged(String message) {
        try {
            database.writeTransaction("discard staged index analyses for project " + projectId,
                    connection -> {
                        SqliteIndexStaging.discard(connection, projectId);
                    });
        } catch (SQLException exception) {
            throw persistence(message, exception);
        }
    }

    private void ensureActive() {
        if (finished) {
            throw new IllegalStateException("La session d'écriture d'index est terminée");
        }
    }
}
