package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.index.FileHasher;
import com.nexus.index.ScannedFile;
import com.nexus.index.scan.ProjectScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mesure hermétique du coût d'un scan borné composé de nombreux petits fichiers, puis de la part
 * du hachage SHA-256 dans le scan initial et dans le scan final de {@code ProjectIndexingService}.
 *
 * <p>La part du hachage est le rapport entre le hachage seul des fichiers retenus (même lecture sans
 * suivi de lien, même borne par fichier que le scanner) et la durée d'un scan complet du même
 * corpus. Elle ne dépend pas de la vitesse absolue de la machine. Deux corpus sont mesurés : des
 * fichiers de quelques dizaines d'octets (coût dominé par l'ouverture) et des fichiers de 16 Kio
 * (taille de sources courantes).</p>
 */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class ProjectScannerCorpusBudgetBenchmarkTest {

    private static final int HASH_SHARE_ROUNDS = 3;
    private static final int MEDIUM_FILE_BYTES = 16 * 1024;
    private static final long MEDIUM_MAX_FILE_SIZE_BYTES = 64L * 1024L;
    private static final int MEDIUM_DIRECTORIES = 100;

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresBoundedSmallFileCorpus() throws Exception {
        String profile = System.getProperty("nexus.scale.benchmark.profile", "ci")
                .trim()
                .toLowerCase(Locale.ROOT);
        boolean full = profile.equals("full");
        if (!full && !profile.equals("ci")) {
            throw new IllegalArgumentException("nexus.scale.benchmark.profile must be ci or full");
        }

        int fileCount = full ? 10_000 : 2_000;
        String source = "class SmallCorpusFixture { int value = 1; }\n";
        long bytesPerFile = source.getBytes(StandardCharsets.UTF_8).length;
        Path root = Files.createDirectory(temporaryDirectory.resolve("scanner-corpus"));

        long creationStarted = System.nanoTime();
        for (int index = 0; index < fileCount; index++) {
            Files.writeString(root.resolve("Fixture%05d.java".formatted(index)), source);
        }
        long creationMs = elapsedMillis(creationStarted);

        long expectedBytes = Math.multiplyExact(bytesPerFile, fileCount);
        ProjectScanner scanner = new ProjectScanner(1024L, fileCount, expectedBytes);
        long scanStarted = System.nanoTime();
        List<ScannedFile> files = scanner.scan(root);
        long scanMs = elapsedMillis(scanStarted);

        assertEquals(fileCount, files.size());
        assertEquals(expectedBytes, files.stream().mapToLong(ScannedFile::sizeBytes).sum());

        Map<String, Object> smallHashing = measureHashShare(scanner, root, fileCount);

        int mediumFileCount = full ? 5_000 : 1_000;
        Path mediumRoot = Files.createDirectory(temporaryDirectory.resolve("scanner-medium-corpus"));
        long mediumBytes = createMediumCorpus(mediumRoot, mediumFileCount);
        ProjectScanner mediumScanner = new ProjectScanner(
                MEDIUM_MAX_FILE_SIZE_BYTES, mediumFileCount + MEDIUM_DIRECTORIES, mediumBytes);
        Map<String, Object> mediumHashing = measureHashShare(mediumScanner, mediumRoot, mediumFileCount);
        mediumHashing.put("files", mediumFileCount);
        mediumHashing.put("bytesPerFile", MEDIUM_FILE_BYTES);
        mediumHashing.put("totalBytes", mediumBytes);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("profile", profile);
        report.put("files", fileCount);
        report.put("bytesPerFile", bytesPerFile);
        report.put("totalBytes", expectedBytes);
        report.put("creationMs", creationMs);
        report.put("scanMs", scanMs);
        report.put("maxFiles", scanner.maxFiles());
        report.put("maxTotalBytes", scanner.maxTotalBytes());
        report.put("hashingSmallFiles", smallHashing);
        report.put("hashingMediumFiles", mediumHashing);

        Path output = Path.of(System.getProperty(
                        "nexus.scanner.scale.benchmark.output",
                        "target/scanner-corpus-benchmark.json"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(output.getParent());
        new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(output.toFile(), report);

        System.out.printf(
                Locale.ROOT,
                "NEXUS scanner corpus benchmark: profile=%s, files=%d, bytes=%d, scan=%dms, "
                        + "hashShareInitial(small)=%.3f, hashShareFinal(small)=%.3f, "
                        + "hashShareInitial(16KiB)=%.3f, hashShareFinal(16KiB)=%.3f, output=%s%n",
                profile,
                fileCount,
                expectedBytes,
                scanMs,
                share(smallHashing, "initialScan"),
                share(smallHashing, "finalScan"),
                share(mediumHashing, "initialScan"),
                share(mediumHashing, "finalScan"),
                output);
    }

    /**
     * Enchaîne {@value #HASH_SHARE_ROUNDS} tours (scan complet puis hachage seul des fichiers
     * retenus). Le premier tour est le scan initial, le dernier le scan final de l'indexation.
     */
    private static Map<String, Object> measureHashShare(ProjectScanner scanner, Path root, int expectedFiles)
            throws IOException {
        List<Long> scanSamples = new ArrayList<>();
        List<Long> hashSamples = new ArrayList<>();
        for (int round = 0; round < HASH_SHARE_ROUNDS; round++) {
            long scanStarted = System.nanoTime();
            List<ScannedFile> files = scanner.scan(root);
            scanSamples.add(elapsedMillis(scanStarted));
            assertEquals(expectedFiles, files.size());

            long hashStarted = System.nanoTime();
            for (ScannedFile file : files) {
                FileHasher.sha256(file.absolutePath(), scanner.maxFileSizeBytes());
            }
            hashSamples.add(elapsedMillis(hashStarted));
        }
        int last = HASH_SHARE_ROUNDS - 1;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("initialScan", round(scanSamples.get(0), hashSamples.get(0)));
        result.put("finalScan", round(scanSamples.get(last), hashSamples.get(last)));
        result.put("scanSamplesMs", scanSamples);
        result.put("hashPassSamplesMs", hashSamples);
        return result;
    }

    private static Map<String, Object> round(long scanMs, long hashPassMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanMs", scanMs);
        result.put("hashPassMs", hashPassMs);
        result.put("hashShare", scanMs <= 0L ? 0.0d : (double) hashPassMs / (double) scanMs);
        return result;
    }

    private static double share(Map<String, Object> hashing, String key) {
        return ((Number) ((Map<?, ?>) hashing.get(key)).get("hashShare")).doubleValue();
    }

    private static long createMediumCorpus(Path root, int fileCount) throws IOException {
        StringBuilder builder = new StringBuilder(MEDIUM_FILE_BYTES);
        builder.append("package bench.medium;\n\nclass MediumCorpusFixture {\n");
        int line = 0;
        while (builder.length() < MEDIUM_FILE_BYTES - 64) {
            builder.append("    int field").append(line++).append(" = ").append(line).append(";\n");
        }
        builder.append("}\n");
        byte[] content = builder.toString().getBytes(StandardCharsets.UTF_8);
        long total = 0L;
        for (int index = 0; index < fileCount; index++) {
            Path directory = root.resolve("d" + (index % MEDIUM_DIRECTORIES));
            Files.createDirectories(directory);
            Files.write(directory.resolve("Medium%05d.java".formatted(index)), content);
            total += content.length;
        }
        return total;
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
