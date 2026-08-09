package com.nexus.security;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Primitives de lecture qui refusent de suivre un lien symbolique sur le
 * composant final au moment exact de l'ouverture du fichier.
 *
 * <p>Ces primitives complètent {@link ProjectPathGuard}. Le guard vérifie la
 * frontière et les composants du chemin ; {@code SafeFileIO} réduit la fenêtre
 * TOCTOU entre cette validation et l'ouverture effective du fichier. Tous les
 * flux publics sont également bornés par la politique de taille projet.</p>
 */
public final class SafeFileIO {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS);

    private SafeFileIO() {
    }

    public static InputStream newInputStreamNoFollow(Path file) throws IOException {
        return newInputStreamNoFollow(file, ProjectFileLimits.maxFileSizeFromEnvironment());
    }

    public static InputStream newInputStreamNoFollow(Path file, long maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        SeekableByteChannel channel = Files.newByteChannel(file, READ_NOFOLLOW);
        return new BoundedInputStream(Channels.newInputStream(channel), file, maxBytes);
    }

    public static BufferedReader newBufferedReaderNoFollow(Path file, Charset charset) throws IOException {
        return new BufferedReader(new InputStreamReader(newInputStreamNoFollow(file), charset));
    }

    public static BufferedReader newBufferedReaderNoFollow(Path file, Charset charset, long maxBytes)
            throws IOException {
        return new BufferedReader(new InputStreamReader(newInputStreamNoFollow(file, maxBytes), charset));
    }

    public static byte[] readBytesNoFollow(Path file) throws IOException {
        return readBytesNoFollow(file, ProjectFileLimits.maxFileSizeFromEnvironment());
    }

    public static byte[] readBytesNoFollow(Path file, long maxBytes) throws IOException {
        try (InputStream input = newInputStreamNoFollow(file, maxBytes);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        }
    }

    public static String readStringNoFollow(Path file) throws IOException {
        return readStringNoFollow(file, ProjectFileLimits.maxFileSizeFromEnvironment());
    }

    public static String readStringNoFollow(Path file, long maxBytes) throws IOException {
        return new String(readBytesNoFollow(file, maxBytes), StandardCharsets.UTF_8);
    }

    private static final class BoundedInputStream extends FilterInputStream {

        private final Path file;
        private final long maxBytes;
        private long consumed;

        private BoundedInputStream(InputStream delegate, Path file, long maxBytes) {
            super(delegate);
            this.file = file;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                record(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        private void record(long bytes) throws IOException {
            consumed += bytes;
            if (consumed > maxBytes) {
                throw new IOException(
                        "Fichier trop volumineux pendant la lecture : " + file
                                + " (maximum " + maxBytes + " octets)");
            }
        }
    }
}
