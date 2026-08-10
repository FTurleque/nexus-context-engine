package com.nexus.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nexus.index.ScannedFile;
import com.nexus.index.scan.ProjectScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Mesure hermétique du coût d'un scan borné composé de nombreux petits fichiers. */
@EnabledIfSystemProperty(named = "nexus.scale.benchmark.enabled", matches = "true")
class ProjectScannerCorpusBudgetBenchmarkTest {

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
                "NEXUS scanner corpus benchmark: profile=%s, files=%d, bytes=%d, scan=%dms, output=%s%n",
                profile,
                fileCount,
                expectedBytes,
                scanMs,
                output);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
