package com.nexus.context.source.git;

import com.nexus.context.ContextFragment;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.search.CandidateType;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(combined.contains("Patch non indexé"));
        assertTrue(combined.contains("localChange"));
        assertFalse(combined.contains("change health endpoint"));
        assertFalse(combined.contains("HealthController { void health"));
    }

    @Test
    void keepsGitContextInsideANestedProjectInAMonorepo() throws Exception {
        Path projectRoot = temporaryDirectory.resolve("backend/order-app");
        Path target = write(projectRoot, "src/OrderService.java", "class OrderService {}\n");
        Path coupled = write(projectRoot, "src/OrderRepository.java", "class OrderRepository {}\n");
        Path outsideProject = write(temporaryDirectory, "frontend/App.ts", "export const app = true;\n");

        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial monorepo");

            Files.writeString(target, "class OrderService { void process() {} }\n");
            Files.writeString(coupled, "class OrderRepository { void save() {} }\n");
            Files.writeString(outsideProject, "export const app = false;\n");
            commitAll(git, "change backend and frontend together");

            Files.writeString(target, "class OrderService { void process() {} void localChange() {} }\n");
        }

        GitContextResult result = new LocalGitContextSourceProvider().discover(new GitContextQuery(
                project(projectRoot),
                "order service",
                List.of(Path.of("src/OrderService.java")),
                true));

        assertTrue(result.repositoryAvailable());
        assertTrue(result.relatedCommits() >= 1);
        String combined = result.fragments().stream()
                .map(ContextFragment::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(combined.contains("src/OrderService.java"));
        assertTrue(combined.contains("src/OrderRepository.java"));
        assertTrue(combined.contains("modifié : src/OrderService.java"));
        assertTrue(combined.contains("localChange"));
        assertFalse(combined.contains("frontend/App.ts"));
        assertFalse(combined.contains("backend/order-app/src/OrderService.java"));
    }

    @Test
    void truncatesLargeTargetDiffDeterministically() throws Exception {
        Path target = write("src/LargeService.java", "class LargeService {}\n");

        try (Git git = Git.init().setDirectory(temporaryDirectory.toFile()).call()) {
            commitAll(git, "initial large diff fixture");
            StringBuilder changed = new StringBuilder("class LargeService {\n");
            for (int index = 0; index < 8_000; index++) {
                changed.append("  String value").append(index).append(" = \"")
                        .append("x".repeat(32)).append("\";\n");
            }
            changed.append("}\n");
            Files.writeString(target, changed);
        }

        GitContextResult result = new LocalGitContextSourceProvider().discover(new GitContextQuery(
                project(),
                "large service",
                List.of(Path.of("src/LargeService.java")),
                true));

        ContextFragment diff = result.fragments().stream()
                .filter(fragment -> fragment.path().toString().replace('\\', '/')
                        .equals(".nexus/git/working-tree-diff.md"))
                .findFirst()
                .orElseThrow();
        String content = diff.content();
        String marker = "... [diff Git tronqué par NEXUS]";
        assertTrue(content.contains(marker));

        int fenceStart = content.indexOf("```diff\n") + "```diff\n".length();
        int fenceEnd = content.indexOf("\n```", fenceStart);
        String renderedPatch = content.substring(fenceStart, fenceEnd);
        assertTrue(renderedPatch.length() <= LocalGitContextSourceProvider.MAX_LOCAL_DIFF_CHARS
                        + 1 + marker.length(),
                "le patch rendu doit rester borné même pour une modification massive");
    }

    @Test
    void fixedCapacityDiffSinkNeverRetainsBytesPastItsCapacity() throws Exception {
        Class<?> sinkClass = Class.forName(
                "com.nexus.context.source.git.LocalGitContextSourceProvider$BoundedOutput");
        Constructor<?> constructor = sinkClass.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        OutputStream sink = (OutputStream) constructor.newInstance(128);

        sink.write(new byte[4_096]);

        Method size = sinkClass.getDeclaredMethod("size");
        Method truncated = sinkClass.getDeclaredMethod("truncated");
        size.setAccessible(true);
        truncated.setAccessible(true);
        assertEquals(128, ((Number) size.invoke(sink)).intValue());
        assertTrue((Boolean) truncated.invoke(sink));
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
        return write(temporaryDirectory, relativePath, content);
    }

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
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
        return project(temporaryDirectory);
    }

    private static ProjectDescriptor project(Path root) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "git-context-test",
                root,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }
}
