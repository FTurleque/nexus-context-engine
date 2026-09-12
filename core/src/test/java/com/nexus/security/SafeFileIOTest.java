package com.nexus.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeFileIOTest {

    @Test
    void forcedFallbackStrictRefusesBeforeOpeningEvenAnOrdinaryFile() throws Exception {
        Path file = temporaryDirectory.resolve("strict.txt");
        Files.writeString(file, "safe");
        AtomicBoolean opened = new AtomicBoolean();
        assertThrows(IOException.class, () -> SafeFileIO.openReadNoFollow(file, true, true, path -> {
            opened.set(true);
            throw new AssertionError("Strict fallback must never open the resource");
        }));
        org.junit.jupiter.api.Assertions.assertFalse(opened.get());
    }

    @Test
    void forcedCompatibilityFallbackReadsOrdinaryFile() throws Exception {
        Path file = temporaryDirectory.resolve("compatible.txt");
        Files.writeString(file, "safe");
        try (var channel = SafeFileIO.openReadNoFollow(file, false, true, SafeFileIOTest::open)) {
            ByteBuffer bytes = ByteBuffer.allocate(4);
            assertEquals(4, channel.read(bytes));
            assertEquals("safe", new String(bytes.array(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void forcedFallbackRejectsFinalAndIntermediateSymlinksBeforeOpen() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-links"));
        Path secret = Files.writeString(outside.resolve("data"), "outside");
        Path directoryLink = temporaryDirectory.resolve("directory-link");
        Path fileLink = temporaryDirectory.resolve("file-link");
        Assumptions.assumeTrue(createSymbolicLink(directoryLink, outside));
        Assumptions.assumeTrue(createSymbolicLink(fileLink, secret));
        for (Path candidate : java.util.List.of(fileLink, directoryLink.resolve("data"))) {
            assertThrows(IOException.class, () -> SafeFileIO.openReadNoFollow(candidate, false, true,
                    path -> { throw new AssertionError("No file may be opened"); }));
        }
    }

    @Test
    void visibleReplacementDuringFallbackOpeningClosesChannelBeforeReading() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("selected"));
        Path candidate = Files.writeString(directory.resolve("data"), "safe");
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-race"));
        Files.writeString(outside.resolve("data"), "outside");
        Path probe = temporaryDirectory.resolve("probe");
        Assumptions.assumeTrue(createSymbolicLink(probe, outside));
        Files.delete(probe);
        SeekableByteChannel[] opened = new SeekableByteChannel[1];
        assertThrows(IOException.class, () -> SafeFileIO.openReadNoFollow(candidate, false, true, path -> {
            Files.move(directory, temporaryDirectory.resolve("saved"));
            Files.createSymbolicLink(directory, outside);
            opened[0] = open(path);
            return opened[0];
        }));
        org.junit.jupiter.api.Assertions.assertFalse(opened[0].isOpen());
    }

    @Test
    void abaRestorationDemonstratesCompatibilityLimitWithoutReadingOutsideContent() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("aba"));
        Path candidate = Files.writeString(directory.resolve("data"), "safe");
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-aba"));
        Files.writeString(outside.resolve("data"), "outside");
        Path probe = temporaryDirectory.resolve("aba-probe");
        Assumptions.assumeTrue(createSymbolicLink(probe, outside));
        Files.delete(probe);
        // L'ouverture effectue une substitution puis restaure le chemin AVANT
        // revalidation. Aucune lecture du channel extérieur n'est effectuée.
        SafeFileIO.ChannelOpener aba = path -> {
            Path saved = temporaryDirectory.resolve("aba-saved");
            Files.move(directory, saved);
            Files.createSymbolicLink(directory, outside);
            try {
                return open(path);
            } finally {
                Files.delete(directory);
                Files.move(saved, directory);
            }
        };
        assertThrows(IOException.class, () -> SafeFileIO.openReadNoFollow(candidate, true, true, aba));
        try (var channel = SafeFileIO.openReadNoFollow(candidate, false, true, aba)) {
            assertTrue(channel.isOpen(), "Compatibility cannot detect restored ABA identities");
            assertEquals(0, channel.position(), "No outside content has been read");
        }
        assertEquals("safe", SafeFileIO.readStringNoFollow(candidate));
    }

    private static SeekableByteChannel open(Path path) throws IOException {
        return Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

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

    @Test
    void fallbackSnapshotDetectsFileReplacementWhenFilesystemProvidesStableIdentity() throws Exception {
        Path file = temporaryDirectory.resolve("replace-me.txt");
        Files.writeString(file, "original");
        Object originalKey = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        Assumptions.assumeTrue(originalKey != null,
                "Le filesystem ne fournit pas de fileKey stable pour cette qualification");

        SafeFileIO.FallbackPathSnapshot snapshot = SafeFileIO.captureFallbackPathSnapshot(file);
        Files.delete(file);
        Files.writeString(file, "replacement");
        Object replacementKey = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
        Assumptions.assumeTrue(replacementKey != null && !originalKey.equals(replacementKey),
                "Le filesystem a réutilisé la même identité pour le fichier remplacé");

        IOException failure = assertThrows(
                IOException.class,
                () -> SafeFileIO.revalidateFallbackPathSnapshot(snapshot));

        assertTrue(failure.getMessage().contains("Identité filesystem modifiée"));
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
