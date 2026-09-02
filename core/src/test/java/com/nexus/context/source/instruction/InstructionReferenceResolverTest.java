package com.nexus.context.source.instruction;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionReferenceResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsReferencesInsideRepositoryStopsCyclesAndRespectsNestedIgnoreRules() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = temporaryDirectory.resolve("outside.md");
        Files.writeString(outside, "OUTSIDE_SECRET");

        Path agents = write(projectRoot, "AGENTS.md", """
                Read @docs/a.md
                Ignore @../outside.md
                Ignore @module/private.md
                """);
        write(projectRoot, "docs/a.md", "A -> @b.md");
        write(projectRoot, "docs/b.md", "B -> @a.md");
        write(projectRoot, "module/.nexusignore", "private.md\n");
        write(projectRoot, "module/private.md", "NESTED_IGNORED_SECRET");

        ProjectDescriptor project = project(projectRoot, "references");

        var references = new InstructionReferenceResolver().resolve(project, agents);

        assertEquals(2, references.size());
        assertTrue(references.stream().anyMatch(reference ->
                reference.relativePath().toString().replace('\\', '/').equals("docs/a.md")));
        assertTrue(references.stream().anyMatch(reference ->
                reference.relativePath().toString().replace('\\', '/').equals("docs/b.md")));
        assertTrue(references.stream().noneMatch(reference -> reference.content().contains("OUTSIDE_SECRET")));
        assertTrue(references.stream().noneMatch(reference ->
                reference.content().contains("NESTED_IGNORED_SECRET")));
    }

    @Test
    void refusesReferencesThatReachOutsideThroughSymbolicLinks() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("symlink-project"));
        Path outside = temporaryDirectory.resolve("symlink-secret.md");
        Files.writeString(outside, "SYMLINK_SECRET");
        Path agents = write(projectRoot, "AGENTS.md", "Read @docs/external.md");
        Path link = projectRoot.resolve("docs/external.md");
        Files.createDirectories(link.getParent());

        Assumptions.assumeTrue(createSymbolicLink(link, outside),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        var references = new InstructionReferenceResolver().resolve(
                project(projectRoot, "symlink-references"),
                agents);

        assertTrue(references.isEmpty());
        assertTrue(references.stream().noneMatch(reference -> reference.content().contains("SYMLINK_SECRET")));
    }

    private static ProjectDescriptor project(Path root, String name) throws Exception {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                root.toRealPath(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return false;
        }
    }
}
