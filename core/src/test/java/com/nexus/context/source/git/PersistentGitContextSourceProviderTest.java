package com.nexus.context.source.git;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentGitContextSourceProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void reusesStableResultAndInvalidatesWorkingTreeIndexAndHead() throws Exception {
        Fixture fixture = repository("cache-invalidation");
        CountingProvider delegate = new CountingProvider();
        PersistentGitContextSourceProvider provider = new PersistentGitContextSourceProvider(delegate, 4);

        GitContextResult first = provider.discover(fixture.query("same query"));
        GitContextResult second = provider.discover(fixture.query("same query"));

        assertEquals(first, second);
        assertEquals(1, delegate.calls);
        assertEquals(1, provider.stats().hits());
        assertEquals(1, provider.stats().entries());

        Files.writeString(fixture.target(), "class Service { int dirty = 2; }\n");
        GitContextResult dirty = provider.discover(fixture.query("same query"));
        assertEquals(2, delegate.calls);
        assertTrue(render(dirty).contains("Patch non indexé"));

        try (Git git = Git.open(fixture.root().toFile())) {
            git.add().addFilepattern("src/Service.java").call();
        }
        GitContextResult staged = provider.discover(fixture.query("same query"));
        assertEquals(3, delegate.calls);
        assertTrue(render(staged).contains("Patch indexé"));

        try (Git git = Git.open(fixture.root().toFile())) {
            git.commit().setMessage("update target").call();
        }
        GitContextResult committed = provider.discover(fixture.query("same query"));
        assertEquals(4, delegate.calls);
        assertFalse(committed.fragments().isEmpty());
    }

    @Test
    void keepsStorageBoundedWithAccessOrderEviction() throws Exception {
        Fixture fixture = repository("cache-bounds");
        CountingProvider delegate = new CountingProvider();
        PersistentGitContextSourceProvider provider = new PersistentGitContextSourceProvider(delegate, 2);

        provider.discover(fixture.query("query one"));
        provider.discover(fixture.query("query two"));
        provider.discover(fixture.query("query three"));

        PersistentGitContextSourceProvider.CacheStats afterFill = provider.stats();
        assertEquals(2, afterFill.entries());
        assertEquals(1, afterFill.evictions());
        assertEquals(3, afterFill.misses());

        provider.discover(fixture.query("query one"));
        assertEquals(4, delegate.calls);
        assertEquals(2, provider.stats().entries());
        assertTrue(provider.stats().evictions() >= 2);
    }

    @Test
    void unavailableRepositoryIsNeverCached() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("not-a-repository"));
        CountingProvider delegate = new CountingProvider();
        PersistentGitContextSourceProvider provider = new PersistentGitContextSourceProvider(delegate, 2);
        GitContextQuery query = new GitContextQuery(
                project(root, "not-git"),
                "query",
                List.of(Path.of("src/Service.java")),
                true);

        GitContextResult first = provider.discover(query);
        GitContextResult second = provider.discover(new GitContextQuery(
                project(root, "not-git"),
                "query",
                List.of(Path.of("src/Service.java")),
                true));

        assertFalse(first.repositoryAvailable());
        assertFalse(second.repositoryAvailable());
        assertEquals(2, delegate.calls);
        assertEquals(0, provider.stats().entries());
    }

    private Fixture repository(String name) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        Path target = root.resolve("src/Service.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "class Service { int version = 1; }\n");

        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "NEXUS Test");
            git.getRepository().getConfig().setString("user", null, "email", "nexus-test@example.test");
            git.getRepository().getConfig().save();
            git.add().addFilepattern("src/Service.java").call();
            git.commit().setMessage("initial target").call();
        }
        return new Fixture(root, target, project(root, name));
    }

    private static ProjectDescriptor project(Path root, String name) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                root,
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);
    }

    private static String render(GitContextResult result) {
        return result.fragments().stream()
                .map(fragment -> fragment.content())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private record Fixture(Path root, Path target, ProjectDescriptor project) {
        GitContextQuery query(String query) {
            return new GitContextQuery(project, query, List.of(Path.of("src/Service.java")), true);
        }
    }

    private static final class CountingProvider implements GitContextSourceProvider {
        private final LocalGitContextSourceProvider delegate = new LocalGitContextSourceProvider();
        private int calls;

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public GitContextResult discover(GitContextQuery query) throws IOException {
            calls++;
            return delegate.discover(query);
        }
    }
}
