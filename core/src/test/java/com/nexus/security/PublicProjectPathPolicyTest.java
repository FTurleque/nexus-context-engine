package com.nexus.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicProjectPathPolicyTest {

    @TempDir
    Path root;

    @Test
    void exposesContainedAbsolutePathAsRepositoryRelative() {
        Path candidate = root.resolve("src/main/App.java");

        assertEquals("src/main/App.java", PublicProjectPathPolicy.expose(root, candidate));
    }

    @Test
    void preservesSafeRelativePath() {
        assertEquals("src/App.java", PublicProjectPathPolicy.expose(root, Path.of("src", "App.java")));
    }

    @Test
    void rejectsAbsolutePathOutsideProjectWithoutEchoingIt() {
        Path outside = root.resolveSibling("secret.txt").toAbsolutePath().normalize();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> PublicProjectPathPolicy.expose(root, outside));

        assertEquals("Result path is outside the project root", exception.getMessage());
    }

    @Test
    void rejectsRelativeTraversalOutsideProject() {
        assertThrows(
                IllegalStateException.class,
                () -> PublicProjectPathPolicy.expose(root, Path.of("..", "secret.txt")));
    }
}
