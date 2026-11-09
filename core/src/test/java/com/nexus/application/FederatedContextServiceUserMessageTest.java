package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Les erreurs qu'un appelant CLI, REST ou MCP provoque sur le contexte fédéré remontent en
 * français par la façade {@link NexusApplication} (règle {@code .claude/rules/java-core.md}).
 */
class FederatedContextServiceUserMessageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void blankFederatedQueryIsRejectedInFrenchThroughTheFacade() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        try (NexusApplication application = NexusApplication.create(paths, SemanticSearchConfiguration.disabled())) {
            List<UUID> ids = readyProjects(paths, 2);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> application.contextAcrossProjects(ids, "   ", 1_000, Set.of(), Map.of(), false));

            assertEquals("La requête ne peut pas être vide", failure.getMessage());
        }
    }

    @Test
    void federatedBudgetErrorsAreReportedInFrenchThroughTheFacade() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        try (NexusApplication application = NexusApplication.create(paths, SemanticSearchConfiguration.disabled())) {
            List<UUID> ids = readyProjects(paths, 2);

            IllegalArgumentException zero = assertThrows(
                    IllegalArgumentException.class,
                    () -> application.contextAcrossProjects(ids, "task", 0, Set.of(), Map.of(), false));
            IllegalArgumentException belowScope = assertThrows(
                    IllegalArgumentException.class,
                    () -> application.contextAcrossProjects(ids, "task", 1, Set.of(), Map.of(), false));

            assertEquals("tokenBudget doit être strictement positif", zero.getMessage());
            assertEquals(
                    "tokenBudget doit être au moins égal au nombre de projets du scope fédéré",
                    belowScope.getMessage());
        }
    }

    @Test
    void emptyFederatedScopeIsRejectedInFrenchThroughTheFacade() throws Exception {
        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        try (NexusApplication application = NexusApplication.create(paths, SemanticSearchConfiguration.disabled())) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> application.contextAcrossProjects(List.of(), "task", 1_000, Set.of(), Map.of(), false));

            assertEquals("La liste projectIds ne peut pas être vide", failure.getMessage());
        }
    }

    private List<UUID> readyProjects(NexusPaths paths, int count) throws Exception {
        SqliteProjectRepository repository = new SqliteProjectRepository(new SqliteDatabase(paths));
        List<UUID> ids = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            UUID id = new UUID(0L, index);
            ids.add(id);
            repository.save(new ProjectDescriptor(
                    id,
                    "project-" + index,
                    temporaryDirectory.resolve("project-" + index),
                    ProjectSourceType.LOCAL,
                    Set.of("java"),
                    Set.of(),
                    null,
                    IndexStatus.READY));
        }
        return ids;
    }
}
