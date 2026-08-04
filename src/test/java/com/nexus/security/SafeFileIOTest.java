package com.nexus.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeFileIOTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsOrdinaryFiles() throws Exception {
        Path file = temporaryDirectory.resolve("safe.txt");
        Files.writeString(file, "safe-content");

        assertEquals("safe-content", SafeFileIO.readStringNoFollow(file));
    }

    @Test
    void refusesASymbolicLinkAtOpenTime() throws Exception {
        Path target = temporaryDirectory.resolve("outside.txt");
        Files.writeString(target, "secret");
        Path link = temporaryDirectory.resolve("link.txt");

        Assumptions.assumeTrue(createSymbolicLink(link, target),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        assertThrows(IOException.class, () -> SafeFileIO.readStringNoFollow(link));
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
