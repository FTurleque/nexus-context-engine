package com.nexus.security;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
 * TOCTOU entre cette validation et l'ouverture effective du fichier.</p>
 */
public final class SafeFileIO {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS);

    private SafeFileIO() {
    }

    public static InputStream newInputStreamNoFollow(Path file) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(file, READ_NOFOLLOW);
        return Channels.newInputStream(channel);
    }

    public static BufferedReader newBufferedReaderNoFollow(Path file, Charset charset) throws IOException {
        return new BufferedReader(new InputStreamReader(newInputStreamNoFollow(file), charset));
    }

    public static String readStringNoFollow(Path file) throws IOException {
        return readStringNoFollow(file, ProjectFileLimits.maxFileSizeFromEnvironment());
    }

    public static String readStringNoFollow(Path file, long maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        try (InputStream input = newInputStreamNoFollow(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    throw new IOException(
                            "Fichier trop volumineux au moment de la lecture : " + file
                                    + " (maximum " + maxBytes + " octets)");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
