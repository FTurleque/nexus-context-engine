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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/**
 * Primitives de lecture qui refusent de suivre les liens symboliques au moment
 * de l'ouverture du fichier.
 *
 * <p>Lorsque le provider de fichiers expose {@link SecureDirectoryStream}, le
 * chemin complet est traversé relativement à des descripteurs de répertoire
 * ouverts. Une substitution concurrente d'un composant intermédiaire ne peut
 * donc plus rediriger l'ouverture vers un autre arbre. Les plateformes ne
 * fournissant pas cette primitive conservent un fallback renforcé qui vérifie
 * chaque composant immédiatement avant l'ouverture finale avec
 * {@link LinkOption#NOFOLLOW_LINKS}.</p>
 *
 * <p>Tous les flux publics sont également bornés par la politique de taille
 * projet. La borne s'applique à tous les octets physiquement traversés par le
 * flux, qu'ils soient retournés via une lecture ou ignorés via
 * {@link InputStream#skip(long)}.</p>
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
        Objects.requireNonNull(file, "file");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }
        SeekableByteChannel channel = openReadNoFollow(file);
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

    private static SeekableByteChannel openReadNoFollow(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root != null) {
            try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
                if (rootStream instanceof SecureDirectoryStream<?> secureRaw) {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) secureRaw;
                    Path relative = root.relativize(absolute);
                    if (relative.getNameCount() == 0) {
                        throw new IOException("Le chemin ne désigne pas un fichier : " + file);
                    }
                    return openSecurely(secureRoot, relative, 0);
                }
            }
        }

        rejectSymbolicLinkComponents(absolute);
        return Files.newByteChannel(absolute, READ_NOFOLLOW);
    }

    private static SeekableByteChannel openSecurely(
            SecureDirectoryStream<Path> directory,
            Path relative,
            int componentIndex) throws IOException {
        Path component = relative.getName(componentIndex);
        if (componentIndex == relative.getNameCount() - 1) {
            return directory.newByteChannel(component, READ_NOFOLLOW);
        }
        try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(
                component,
                LinkOption.NOFOLLOW_LINKS)) {
            return openSecurely(child, relative, componentIndex + 1);
        }
    }

    private static void rejectSymbolicLinkComponents(Path absolute) throws IOException {
        Path root = absolute.getRoot();
        if (root == null) {
            throw new IOException("Chemin sans racine de système de fichiers : " + absolute);
        }
        Path current = root;
        for (Path component : root.relativize(absolute)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Lien symbolique interdit pendant la lecture : " + current);
            }
        }
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
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            int boundedLength = (int) boundedTraversal(length);
            int read = super.read(buffer, offset, boundedLength);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        @Override
        public long skip(long byteCount) throws IOException {
            if (byteCount <= 0) {
                return 0L;
            }
            long skipped = super.skip(boundedTraversal(byteCount));
            if (skipped > 0) {
                record(skipped);
            }
            return skipped;
        }

        /**
         * Autorise au plus le budget restant plus un octet sentinelle. Cet octet
         * permet de distinguer une vraie EOF à la frontière exacte d'un contenu
         * qui dépasse la borne, sans laisser une opération bulk/skip traverser
         * arbitrairement loin au-delà du budget avant le rejet.
         */
        private long boundedTraversal(long requested) {
            long remaining = maxBytes - consumed;
            long detectable = remaining == Long.MAX_VALUE ? Long.MAX_VALUE : remaining + 1L;
            return Math.min(requested, detectable);
        }

        private void record(long bytes) throws IOException {
            if (bytes > maxBytes - consumed) {
                throw tooLarge();
            }
            consumed += bytes;
        }

        private IOException tooLarge() {
            return new IOException(
                    "Fichier trop volumineux pendant la lecture : " + file
                            + " (maximum " + maxBytes + " octets)");
        }
    }
}
