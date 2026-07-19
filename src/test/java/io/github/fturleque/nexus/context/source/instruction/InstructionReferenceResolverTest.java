package io.github.fturleque.nexus.context.source.instruction;

import io.github.fturleque.nexus.project.IndexStatus;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        ProjectDescriptor project = new ProjectDescriptor(
                UUID.randomUUID(),
                "references",
                projectRoot.toRealPath(),
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);

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

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
