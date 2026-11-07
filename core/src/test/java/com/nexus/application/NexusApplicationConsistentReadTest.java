package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRepository;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gabarit de lecture cohérente (ADR-0052) appliqué à {@code inspect} : READY à l'entrée,
 * verrou partagé, session de lecture, revalidation READY avant de rendre le résultat.
 */
class NexusApplicationConsistentReadTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectRequiresAReadyProjectBeforeReading() throws Exception {
        try (NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")),
                SemanticSearchConfiguration.disabled())) {
            ProjectDescriptor project = application.registerProject(sampleProject(), "inspect-not-ready");

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> application.inspect(project.id()));
            assertTrue(failure.getMessage().contains("n'est pas READY"), failure.getMessage());
        }
    }

    @Test
    void inspectRejectsAProjectThatLeavesReadyDuringTheRead() throws Exception {
        try (NexusApplication real = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")),
                SemanticSearchConfiguration.disabled())) {
            ProjectDescriptor registered = real.registerProject(sampleProject(), "inspect-revalidation");
            real.index(registered.id(), false, false);
            ProjectDescriptor ready = real.getProject(registered.id());
            assertEquals(IndexStatus.READY, ready.indexStatus());

            ProjectRepository projects = field(real, "projectRepository", ProjectRepository.class);
            IndexRepository delegate = field(real, "indexRepository", IndexRepository.class);
            AtomicBoolean demoteDuringRead = new AtomicBoolean(true);
            IndexRepository demoting = demotingDuringStatistics(delegate, projects, demoteDuringRead);
            NexusApplication application = withIndexRepository(real, demoting);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> application.inspect(ready.id()));
            assertTrue(failure.getMessage().contains("n'est pas READY"), failure.getMessage());

            // Le verrou de lecture est libéré malgré le refus : une écriture peut le prendre.
            ProjectIndexLockManager locks = field(real, "projectIndexLockManager", ProjectIndexLockManager.class);
            try (ProjectIndexLockManager.LockHandle ignored = locks.acquire(ready.id())) {
                projects.save(withStatus(ready, IndexStatus.READY));
            }

            demoteDuringRead.set(false);
            IndexStatistics statistics = application.inspect(ready.id());
            assertTrue(statistics.files() > 0, "Le projet READY doit exposer ses fichiers indexés");
        }
    }

    private Path sampleProject() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(root.resolve("README.md"), "# Démo\n\nLecture cohérente.\n");
        return root;
    }

    private static IndexRepository demotingDuringStatistics(
            IndexRepository delegate,
            ProjectRepository projects,
            AtomicBoolean demote) {
        return (IndexRepository) Proxy.newProxyInstance(
                IndexRepository.class.getClassLoader(),
                new Class<?>[] {IndexRepository.class},
                (proxy, method, arguments) -> {
                    if (demote.get() && "statistics".equals(method.getName())) {
                        UUID projectId = (UUID) arguments[0];
                        ProjectDescriptor current = projects.findById(projectId).orElseThrow();
                        projects.save(withStatus(current, IndexStatus.INDEXING));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static NexusApplication withIndexRepository(NexusApplication source, IndexRepository indexRepository)
            throws ReflectiveOperationException {
        return new NexusApplication(
                field(source, "projectRepository", ProjectRepository.class),
                indexRepository,
                field(source, "projectRegistry", com.nexus.project.ProjectRegistry.class),
                field(source, "indexingService", com.nexus.index.service.ProjectIndexingService.class),
                field(source, "projectIndexLockManager", ProjectIndexLockManager.class),
                field(source, "searchService", com.nexus.search.SearchService.class),
                field(source, "federatedSearchService", com.nexus.search.FederatedSearchService.class),
                field(source, "contextBuilder", com.nexus.context.ContextBuilder.class),
                field(source, "federatedContextService", com.nexus.context.FederatedContextService.class),
                field(source, "searchIndex", com.nexus.search.SearchIndex.class),
                null,
                false);
    }

    private static <T> T field(NexusApplication application, String name, Class<T> type)
            throws ReflectiveOperationException {
        Field field = NexusApplication.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(application));
    }

    private static ProjectDescriptor withStatus(ProjectDescriptor project, IndexStatus status) {
        return new ProjectDescriptor(
                project.id(),
                project.name(),
                project.rootPath(),
                project.sourceType(),
                project.languages(),
                project.technologies(),
                project.lastIndexedAt(),
                status);
    }
}
