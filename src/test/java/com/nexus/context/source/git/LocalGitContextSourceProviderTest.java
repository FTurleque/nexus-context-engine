package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGitContextSourceProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsOnlyGitContextRelatedToTargetPaths() throws Exception {
        Path target = write("src/OrderService.java", "class OrderService {}\n");
        Path coupled = write("src/OrderRepository.java", "class OrderRepository {}\n");
        Path unrelated = write("src/HealthController.java", "class HealthController {}\n");

        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial");

            Files.writeString(target, "class OrderService { void process() {} }\n");
            Files.writeString(coupled, "class OrderRepository { void save() {} }\n");
            commitAll(git, "change order workflow");

            Files.writeString(unrelated, "class HealthController { void health() {} }\n");
            commitAll(git, "change health endpoint");

            Files.writeString(target, "class OrderService { void process() {} void localChange() {} }\n");
        }

        GitContextResult result = new LocalGitContextSourceProvider().discover(new GitContextQuery(
                project(),
                "change order workflow",
                List.of(Path.of("src/OrderService.java")),
                true));

        assertTrue(result.enabled());
        assertTrue(result.repositoryAvailable());
        assertTrue(result.commitsInspected() >= 2);
        assertTrue(result.relatedCommits() >= 1);
        assertTrue(result.coChangeLinks() >= 1);
        assertTrue(result.fragments().stream().allMatch(fragment -> fragment.type() == CandidateType.GIT));

        String combined = result.fragments().stream()
                .map(ContextFragment::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(combined.contains("change order workflow"));
        assertTrue(combined.contains("src/OrderRepository.java"));
        assertTrue(combined.contains("modifié : src/OrderService.java"));
        assertFalse(combined.contains("change health endpoint"));
    }

    @Test
    void degradesGracefullyOutsideAGitRepository() throws Exception {
        write("src/Service.java", "class Service {}\n");

        GitContextResult result = new LocalGitContextSourceProvider().discover(new GitContextQuery(
                project(),
                "service",
                List.of(Path.of("src/Service.java")),
                true));

        assertTrue(result.enabled());
        assertFalse(result.repositoryAvailable());
        assertTrue(result.fragments().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }

    private Path write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static void commitAll(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit()
                .setMessage(message)
                .setAuthor("NEXUS Test", "nexus@example.test")
                .setCommitter("NEXUS Test", "nexus@example.test")
                .call();
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "git-context-test",
                temporaryDirectory,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }
}
