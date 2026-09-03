package com.nexus.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FilesystemSemanticsQualificationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsTheQualifiedRunnerFilesystem() throws Exception {
        FileStore store = Files.getFileStore(temporaryDirectory);
        System.out.printf(
                "NEXUS filesystem qualification: os=%s store=%s type=%s%n",
                System.getProperty("os.name"),
                store.name(),
                store.type());
        assertTrue(Files.isDirectory(temporaryDirectory));
    }

    @Test
    void rejectsLexicalTraversalAndAncestorSymlinkEscape() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "outside-secret");
        ProjectPathGuard guard = new ProjectPathGuard(root);

        assertThrows(IOException.class, () -> guard.resolve(Path.of("..", "outside", "secret.txt")));

        Path linkedDirectory = root.resolve("linked-outside");
        requireSymbolicLink(linkedDirectory, outside);
        assertThrows(IOException.class, () -> guard.requireRegularFile(linkedDirectory.resolve("secret.txt")));
    }

    @Test
    void failsClosedWhenAValidatedFinalFileBecomesASymlinkBeforeOpen() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("race-project"));
        Path candidate = root.resolve("candidate.txt");
        Files.writeString(candidate, "trusted-before-race");
        Path outside = temporaryDirectory.resolve("race-secret.txt");
        Files.writeString(outside, "must-not-be-read");

        ProjectPathGuard guard = new ProjectPathGuard(root);
        Path validated = guard.requireRegularFile(candidate);
        assertEquals("trusted-before-race", SafeFileIO.readStringNoFollow(validated));

        Files.delete(candidate);
        requireSymbolicLink(candidate, outside);

        assertThrows(IOException.class, () -> SafeFileIO.readStringNoFollow(validated));
        assertEquals("must-not-be-read", Files.readString(outside));
    }

    private static void requireSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            fail("La qualification filesystem exige le support des symlinks sur ce runner", unavailable);
        }
    }
}
