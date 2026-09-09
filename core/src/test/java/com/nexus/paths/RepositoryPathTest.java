package com.nexus.paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryPathTest {
    @TempDir Path root;

    @Test void encodesComponentsAndRoundTripsOnTheHostFilesystem() {
        Path relative = root.getFileSystem().getPath("a", "b.java");
        assertEquals("a/b.java", RepositoryPath.fromPath(relative).value());
        assertEquals(root.resolve(relative), RepositoryPath.fromPath(relative).resolve(root));
        Path backslash = root.getFileSystem().getPath("a\\b.java");
        boolean posix = root.getFileSystem().getSeparator().equals("/");
        assertEquals(posix ? "a\\b.java" : "a/b.java", RepositoryPath.fromPath(backslash).value());
        if (posix) {
            assertNotEquals(RepositoryPath.fromPath(relative), RepositoryPath.fromPath(backslash));
            assertEquals(root.resolve(backslash), new RepositoryPath("a\\b.java").resolve(root));
            assertEquals(" space /é:foo\\bar ", RepositoryPath.fromPath(Path.of(" space ", "é:foo\\bar ")).value());
        } else {
            assertThrows(IllegalArgumentException.class, () -> resolveInvalid(root, "a\\b.java"));
            assertThrows(IllegalArgumentException.class, () -> resolveInvalid(root, "C:secret"));
        }
        assertEquals("", RepositoryPath.encode(Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> encodeInvalid(Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> encodeInvalid(root));
    }

    @Test void refusesInvalidProviderSyntaxWithoutRepair() {
        for (String invalid : List.of("", "/etc/file", "../file", "a/../file", "./a", "a/./b",
                "a//b", "a/", "C:/Users/secret", "D:\\secret", "C:secret", "\\\\server\\share", "a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> parseInvalidProvider(invalid), invalid);
        }
        assertEquals("a\\b.java", RepositoryPath.fromProvider("a\\b.java").value());
        assertEquals("a/b.java", RepositoryPath.fromProvider("a/b.java").value());
    }

    private static Path resolveInvalid(Path root, String value) {
        return new RepositoryPath(value).resolve(root);
    }

    private static String encodeInvalid(Path value) {
        return RepositoryPath.fromPath(value).value();
    }

    private static RepositoryPath parseInvalidProvider(String value) {
        return RepositoryPath.fromProvider(value);
    }
}
