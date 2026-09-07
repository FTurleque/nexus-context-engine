import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies that an extracted tool directory is an exact content projection of a
 * previously checksum-pinned ZIP archive. Intended for Java source-file mode so
 * Maven Wrapper can validate its own cached Maven installation before Maven is
 * available.
 */
public final class ToolArchiveVerifier {

    private static final Set<OpenOption> READ_NOFOLLOW = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS);

    private ToolArchiveVerifier() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !"zip".equals(args[0])) {
            System.err.println("Usage: java ToolArchiveVerifier.java zip <archive.zip> <extracted-root> <archive-prefix>");
            System.exit(2);
        }

        verifyZip(Path.of(args[1]), Path.of(args[2]), args[3]);
        System.out.println("Verified extracted tool cache: " + Path.of(args[2]).toAbsolutePath().normalize());
    }

    static void verifyZip(Path archive, Path extractedRoot, String archivePrefix) throws IOException {
        Path normalizedArchive = archive.toAbsolutePath().normalize();
        Path root = extractedRoot.toAbsolutePath().normalize();
        String prefix = normalizePrefix(archivePrefix);
        if (!Files.isRegularFile(normalizedArchive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Tool archive is missing or unsafe: " + normalizedArchive);
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Extracted tool root is missing or unsafe: " + root);
        }

        Set<String> expectedFiles = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(normalizedArchive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String relativeName = name.substring(prefix.length());
                if (relativeName.isEmpty() || entry.isDirectory()) {
                    continue;
                }
                Path relative = safeRelativePath(relativeName);
                String key = repositoryPath(relative);
                if (!expectedFiles.add(key)) {
                    throw new IOException("Duplicate tool archive entry: " + relativeName);
                }
                Path target = root.resolve(relative).normalize();
                requireInside(root, target);
                if (Files.isSymbolicLink(target)
                        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Extracted tool file is missing, symbolic or non-regular: " + target);
                }
                if (entry.getSize() >= 0L && Files.size(target) != entry.getSize()) {
                    throw new IOException("Extracted tool file size differs from pinned archive: " + target);
                }
                try (InputStream expected = zip.getInputStream(entry);
                     SeekableByteChannel channel = Files.newByteChannel(target, READ_NOFOLLOW);
                     InputStream actual = Channels.newInputStream(channel)) {
                    compare(expected, actual, target);
                }
            }
        }

        if (expectedFiles.isEmpty()) {
            throw new IOException("Pinned archive prefix contains no files: " + prefix);
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()) {
                    throw new IOException("Symbolic directory in extracted tool cache: " + directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("Unsafe entry in extracted tool cache: " + file);
                }
                String relative = repositoryPath(root.relativize(file));
                if (!expectedFiles.contains(relative)) {
                    throw new IOException("Unexpected file in extracted tool cache: " + file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String normalizePrefix(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Archive prefix must not be blank");
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
            throw new IOException("Unsafe archive prefix: " + value);
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static Path safeRelativePath(String value) throws IOException {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IOException("Absolute entry in tool archive: " + value);
        }
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute()
                || relative.getNameCount() == 0
                || relative.startsWith("..")
                || !repositoryPath(relative).equals(normalized)) {
            throw new IOException("Unsafe entry in tool archive: " + value);
        }
        return relative;
    }

    private static void requireInside(Path root, Path target) throws IOException {
        if (!target.startsWith(root)) {
            throw new IOException("Tool archive entry escapes extracted root: " + target);
        }
    }

    private static void compare(InputStream expected, InputStream actual, Path target) throws IOException {
        byte[] expectedBuffer = new byte[16 * 1024];
        byte[] actualBuffer = new byte[16 * 1024];
        while (true) {
            int expectedRead = expected.readNBytes(expectedBuffer, 0, expectedBuffer.length);
            int actualRead = actual.readNBytes(actualBuffer, 0, actualBuffer.length);
            if (expectedRead != actualRead) {
                throw new IOException("Extracted tool file differs from pinned archive: " + target);
            }
            if (expectedRead == 0) {
                return;
            }
            for (int index = 0; index < expectedRead; index++) {
                if (expectedBuffer[index] != actualBuffer[index]) {
                    throw new IOException("Extracted tool file differs from pinned archive: " + target);
                }
            }
        }
    }

    private static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
