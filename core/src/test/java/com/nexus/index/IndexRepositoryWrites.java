package com.nexus.index;

import com.nexus.persistence.sqlite.SqliteBatchInsert;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Écriture d'analyses de fichiers dans un test par le seul chemin du port : session ouverte, mise en
 * attente par lots de {@link SqliteBatchInsert#ROW_LIMIT} fichiers, publication avec les suppressions,
 * fermeture. Les faits et la génération écrits sont ceux d'une indexation.
 */
public final class IndexRepositoryWrites {

    private IndexRepositoryWrites() {
    }

    public static void apply(
            IndexRepository repository,
            UUID projectId,
            List<IndexedFileUpdate> updates,
            Set<String> removedPaths) {
        try (IndexWriteSession session = repository.openWriteSession(projectId)) {
            for (int from = 0; from < updates.size(); from += SqliteBatchInsert.ROW_LIMIT) {
                session.stage(updates.subList(from, Math.min(updates.size(), from + SqliteBatchInsert.ROW_LIMIT)));
            }
            session.publish(removedPaths);
        }
    }
}
