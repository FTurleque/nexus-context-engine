package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.context.ContextBuildingException;
import com.nexus.context.FederatedContextBundle;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusApplicationFederatedContextTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsFederatedTaskContextUnderOneBudgetWithExplicitProvenance() throws Exception {
        Path projectARoot = Files.createDirectories(temporaryDirectory.resolve("project-a"));
        Path projectBRoot = Files.createDirectories(temporaryDirectory.resolve("project-b"));
        String sharedPath = "src/main/java/demo/InvoiceService.java";
        write(projectARoot, sharedPath, """
                package demo;
                public class InvoiceService {
                    public void reconcileInvoice() {
                        System.out.println("project-a billing reconciliation");
                    }
                }
                """);
        write(projectBRoot, sharedPath, """
                package demo;
                public class InvoiceService {
                    public void reconcileInvoice() {
                        System.out.println("project-b ledger reconciliation");
                    }
                }
                """);

        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home")));
        ProjectDescriptor projectA = application.registerProject(projectARoot, "project-a");
        ProjectDescriptor projectB = application.registerProject(projectBRoot, "project-b");
        application.index(projectA.id(), false, false);
        application.index(projectB.id(), false, false);

        NexusApplication.FederatedContextOperation operation = application.contextAcrossProjects(
                List.of(projectA.id(), projectB.id()),
                "reconcileInvoice",
                240,
                Set.of(CandidateType.FILE, CandidateType.SYMBOL),
                Map.of("mode", "federated"),
                true);

        FederatedContextBundle bundle = operation.bundle();
        assertFalse(bundle.items().isEmpty());
        assertTrue(bundle.estimatedTokens() <= bundle.tokenBudget());
        assertEquals(240, bundle.tokenBudget());
        assertEquals(
                Set.of(projectA.id(), projectB.id()),
                bundle.items().stream()
                        .map(item -> item.project().id())
                        .collect(Collectors.toSet()));
        assertTrue(bundle.items().stream().allMatch(item -> !item.item().path().isAbsolute()));

        Map<Path, Set<UUID>> provenanceByRelativePath = bundle.items().stream()
                .collect(Collectors.groupingBy(
                        item -> item.item().path(),
                        Collectors.mapping(item -> item.project().id(), Collectors.toSet())));
        assertTrue(provenanceByRelativePath.values().stream()
                .anyMatch(projectIds -> projectIds.equals(Set.of(projectA.id(), projectB.id()))));

        assertEquals("global-ranking-no-static-project-quota", bundle.metadata().get("budgetPolicy"));
        assertEquals(Boolean.FALSE, bundle.metadata().get("crossProjectDeduplication"));
        assertEquals(Boolean.FALSE, bundle.metadata().get("projectLocalSourcesIncluded"));
        assertEquals(Map.of("mode", "federated"), bundle.metadata().get("constraints"));

        NexusApplication.FederatedContextOperation repeated = application.contextAcrossProjects(
                List.of(projectA.id(), projectB.id()),
                "reconcileInvoice",
                240,
                Set.of(CandidateType.FILE, CandidateType.SYMBOL),
                Map.of("mode", "federated"),
                true);
        assertEquals(bundle.items(), repeated.bundle().items());
        assertEquals(bundle.estimatedTokens(), repeated.bundle().estimatedTokens());
    }

    @Test
    void rejectsProjectLocalSourcesUntilAFederatedPolicyIsDefined() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        write(projectRoot, "src/main/java/demo/SearchService.java", """
                package demo;
                public class SearchService {
                    public void search() {}
                }
                """);

        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home-local-sources")));
        ProjectDescriptor project = application.registerProject(projectRoot, "project");
        application.index(project.id(), false, false);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> application.contextAcrossProjects(
                        List.of(project.id()),
                        "search",
                        200,
                        Set.of(CandidateType.GIT),
                        Map.of(),
                        true));

        assertTrue(exception.getMessage().contains("GIT"));
        assertTrue(exception.getMessage().contains("sources projet-locales"));
    }

    @Test
    void requiresEveryProjectToBeIndexed() throws Exception {
        Path readyRoot = Files.createDirectories(temporaryDirectory.resolve("ready"));
        Path pendingRoot = Files.createDirectories(temporaryDirectory.resolve("pending"));
        write(readyRoot, "src/main/java/demo/ReadyService.java", """
                package demo;
                public class ReadyService {
                    public void execute() {}
                }
                """);
        write(pendingRoot, "src/main/java/demo/PendingService.java", """
                package demo;
                public class PendingService {
                    public void execute() {}
                }
                """);

        NexusApplication application = NexusApplication.create(
                new NexusPaths(temporaryDirectory.resolve("nexus-home-ready")));
        ProjectDescriptor ready = application.registerProject(readyRoot, "ready");
        ProjectDescriptor pending = application.registerProject(pendingRoot, "pending");
        application.index(ready.id(), false, false);

        ContextBuildingException exception = assertThrows(
                ContextBuildingException.class,
                () -> application.contextAcrossProjects(
                        List.of(ready.id(), pending.id()),
                        "execute",
                        200,
                        Set.of(),
                        Map.of(),
                        false));

        assertTrue(exception.getMessage().contains("pending"));
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
