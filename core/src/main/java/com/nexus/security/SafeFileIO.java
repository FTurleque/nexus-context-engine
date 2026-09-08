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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
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
 * fournissant pas cette primitive conservent un fallback renforcé : tous les
 * composants sont vérifiés immédiatement avant l'ouverture, leur chemin réel et
 * leur identité filesystem sont capturés, puis revalidés après l'ouverture. Une
 * substitution concurrente visible pendant cette fenêtre ferme donc le channel
 * et échoue avant toute lecture.</p>
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

        FallbackPathSnapshot snapshot = captureFallbackPathSnapshot(absolute);
        SeekableByteChannel channel = Files.newByteChannel(absolute, READ_NOFOLLOW);
        try {
            revalidateFallbackPathSnapshot(snapshot);
            return channel;
        } catch (IOException validationFailure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                validationFailure.addSuppressed(closeFailure);
            }
            throw validationFailure;
        }
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

    static FallbackPathSnapshot captureFallbackPathSnapshot(Path file) throws IOException {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root == null) {
            throw new IOException("Chemin sans racine de système de fichiers : " + absolute);
        }

        Path relative = root.relativize(absolute);
        if (relative.getNameCount() == 0) {
            throw new IOException("Le chemin ne désigne pas un fichier : " + absolute);
        }

        List<ComponentIdentity> identities = new ArrayList<>(relative.getNameCount());
        Path current = root;
        for (int index = 0; index < relative.getNameCount(); index++) {
            current = current.resolve(relative.getName(index));
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Lien symbolique interdit pendant la lecture : " + current);
            }

            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            boolean finalComponent = index == relative.getNameCount() - 1;
            if (!finalComponent && !attributes.isDirectory()) {
                throw new IOException("Composant de chemin non répertoire pendant la lecture : " + current);
            }
            if (finalComponent && !attributes.isRegularFile()) {
                throw new IOException("Fichier régulier attendu pendant la lecture : " + current);
            }

            identities.add(new ComponentIdentity(
                    current,
                    current.toRealPath(LinkOption.NOFOLLOW_LINKS),
                    attributes.fileKey(),
                    attributes.isDirectory(),
                    attributes.isRegularFile()));
        }
        return new FallbackPathSnapshot(absolute, List.copyOf(identities));
    }

    static void revalidateFallbackPathSnapshot(FallbackPathSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        for (ComponentIdentity identity : snapshot.components()) {
            Path path = identity.path();
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Lien symbolique apparu pendant l'ouverture : " + path);
            }

            BasicFileAttributes attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (identity.directory() != attributes.isDirectory()
                    || identity.regularFile() != attributes.isRegularFile()) {
                throw new IOException("Type de composant modifié pendant l'ouverture : " + path);
            }

            Path realPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!identity.realPath().equals(realPath)) {
                throw new IOException("Chemin réel modifié pendant l'ouverture : " + path);
            }

            Object previousFileKey = identity.fileKey();
            if (previousFileKey != null && !previousFileKey.equals(attributes.fileKey())) {
                throw new IOException("Identité filesystem modifiée pendant l'ouverture : " + path);
            }
        }
    }

    static final class FallbackPathSnapshot {
        private final Path file;
        private final List<ComponentIdentity> components;

        private FallbackPathSnapshot(Path file, List<ComponentIdentity> components) {
            this.file = Objects.requireNonNull(file, "file");
            this.components = List.copyOf(Objects.requireNonNull(components, "components"));
        }

        Path file() {
            return file;
        }

        private List<ComponentIdentity> components() {
            return components;
        }
    }

    private record ComponentIdentity(
            Path path,
            Path realPath,
            Object fileKey,
            boolean directory,
            boolean regularFile) {
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
