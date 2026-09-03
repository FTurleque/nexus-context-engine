package com.nexus.context.source.git;

import com.nexus.context.source.ContextDiscoveryLimitExceededException;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentGitContextSourceProviderBudgetTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void abortsFingerprintWhileJgitStreamsDiffPastDiscoveryBudget() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("repo"));
        Path source = root.resolve("src/Service.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Service { int value = 1; }\n");

        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "NEXUS Test");
            git.getRepository().getConfig().setString("user", null, "email", "nexus-test@example.test");
            git.getRepository().getConfig().save();
            git.add().addFilepattern("src/Service.java").call();
            git.commit().setMessage("initial").call();
        }

        Files.writeString(source, "class Service {\n" + "    int value = 2;\n".repeat(10_000) + "}\n");

        ProjectDescriptor project = new ProjectDescriptor(
                UUID.randomUUID(),
                "git-budget",
                root,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
        var budget = new ContextDiscoveryLimits(1_000, 1_000, 1_024, 10_000).newBudget();
        GitContextQuery query = new GitContextQuery(
                project,
                "service",
                List.of(Path.of("src/Service.java")),
                true,
                budget);

        ContextDiscoveryLimitExceededException failure = assertThrows(
                ContextDiscoveryLimitExceededException.class,
                () -> new PersistentGitContextSourceProvider(new LocalGitContextSourceProvider(), 4).discover(query));

        assertTrue(failure.getMessage().contains("octets cumulés"), failure.getMessage());
        assertTrue(budget.snapshot().cumulativeBytes() <= 1_024L);
    }
}
