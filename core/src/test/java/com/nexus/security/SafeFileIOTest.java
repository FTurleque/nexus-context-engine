package com.nexus.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void refusesContentThatExceedsTheReadLimit() throws Exception {
        Path file = temporaryDirectory.resolve("large.txt");
        Files.writeString(file, "0123456789");

        IOException failure = assertThrows(
                IOException.class,
                () -> SafeFileIO.readStringNoFollow(file, 5L));

        assertTrue(failure.getMessage().contains("maximum 5 octets"));
    }

    @Test
    void exactBoundarySupportsBulkReadAndEof() throws Exception {
        Path file = temporaryDirectory.resolve("exact-read.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5});

        try (InputStream input = SafeFileIO.newInputStreamNoFollow(file, 5L)) {
            assertEquals(5, input.readNBytes(5).length);
            assertEquals(-1, input.read());
        }
    }

    @Test
    void readNBytesCannotCrossTheSharedBudget() throws Exception {
        Path file = temporaryDirectory.resolve("bounded-read.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6});

        try (InputStream input = SafeFileIO.newInputStreamNoFollow(file, 5L)) {
            IOException failure = assertThrows(IOException.class, () -> input.readNBytes(6));
            assertTrue(failure.getMessage().contains("maximum 5 octets"));
        }
    }

    @Test
    void skipConsumesTheSameBudgetAtTheExactBoundary() throws Exception {
        Path file = temporaryDirectory.resolve("exact-skip.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5});

        try (InputStream input = SafeFileIO.newInputStreamNoFollow(file, 5L)) {
            assertEquals(5L, input.skip(5L));
            assertEquals(-1, input.read());
        }
    }

    @Test
    void skipCannotCrossTheReadLimit() throws Exception {
        Path file = temporaryDirectory.resolve("bounded-skip.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6});

        try (InputStream input = SafeFileIO.newInputStreamNoFollow(file, 5L)) {
            IOException failure = assertThrows(IOException.class, () -> input.skip(6L));
            assertTrue(failure.getMessage().contains("maximum 5 octets"));
        }
    }

    @Test
    void mixedReadAndSkipShareOneBudget() throws Exception {
        Path file = temporaryDirectory.resolve("mixed.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6});

        try (InputStream input = SafeFileIO.newInputStreamNoFollow(file, 5L)) {
            assertEquals(2, input.readNBytes(2).length);
            IOException failure = assertThrows(IOException.class, () -> input.skip(4L));
            assertTrue(failure.getMessage().contains("maximum 5 octets"));
        }
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

    @Test
    void refusesASymbolicLinkInAnIntermediatePathComponent() throws Exception {
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path safe = temporaryDirectory.resolve("safe");
        Files.createDirectories(safe);
        Path redirect = safe.resolve("redirect");

        Assumptions.assumeTrue(createSymbolicLink(redirect, outside),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        assertThrows(IOException.class, () -> SafeFileIO.readStringNoFollow(redirect.resolve("secret.txt")));
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
