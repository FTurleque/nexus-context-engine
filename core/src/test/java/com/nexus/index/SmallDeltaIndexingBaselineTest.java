package com.nexus.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.project.ProjectDescriptor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness opt-in qui mesure une indexation incrémentale avec un petit delta
 * sur une copie temporaire contrôlée d'un repository réel.
 */
class SmallDeltaIndexingBaselineTest {

    private static final String PROBE_CLASS_NAME = "NexusIteration16DeltaProbe";
    private static final Set<String> COPY_EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", ".nexus", "target", "build", "node_modules");

    @TempDir
    Path temporaryDirectory;

    @Test
    void measuresSmallDeltaOnControlledCopy() throws Exception {
        String configuredSource = System.getProperty("nexus.delta.source", "").trim();
        Assumptions.assumeFalse(
                configuredSource.isBlank(),
                "Baseline opt-in : fournir -Dnexus.delta.source=<repository>");

        Path sourceRoot = Path.of(configuredSource).toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException("Repository source introuvable : " + sourceRoot);
        }

        String modifiedRelativePath = requireRelativePath("nexus.delta.modified");
        String addedRelativePath = requireRelativePath("nexus.delta.added");
        if (!addedRelativePath.toLowerCase().endsWith(".java")) {
            throw new IllegalArgumentException("nexus.delta.added doit cibler un fichier Java : " + addedRelativePath);
        }

        Path output = Path.of(System.getProperty(
                        "nexus.delta.output",
                        "target/iteration-16-small-delta.json"))
                .toAbsolutePath()
                .normalize();

        Path controlledCopy = temporaryDirectory.resolve("controlled-copy");
        copyRepository(sourceRoot, controlledCopy);

        Path modifiedFile = resolveInside(controlledCopy, modifiedRelativePath);
        Path addedFile = resolveInside(controlledCopy, addedRelativePath);
        if (!Files.isRegularFile(modifiedFile)) {
            throw new IllegalArgumentException("Fichier à modifier introuvable dans la copie : " + modifiedRelativePath);
        }
        if (Files.exists(addedFile)) {
            throw new IllegalArgumentException("Le fichier de delta existe déjà : " + addedRelativePath);
        }

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        NexusApplication application = NexusApplication.create(paths);
        ProjectDescriptor project = application.registerProject(controlledCopy, "iteration-16-small-delta");

        NexusApplication.IndexOperation fullIndex = application.index(project.id(), true, false);
        NexusApplication.IndexOperation noChangeIndex = application.index(project.id(), false, false);

        assertTrue(fullIndex.report().fullSearchRebuild());
        assertFalse(noChangeIndex.report().fullSearchRebuild());
        assertEquals(0, noChangeIndex.report().changedFiles());
        assertEquals(0, noChangeIndex.report().removedFiles());

        String originalModifiedContent = Files.readString(modifiedFile, StandardCharsets.UTF_8);
        Files.writeString(
                modifiedFile,
                System.lineSeparator() + "// NEXUS_ITERATION_16_SMALL_DELTA" + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        Files.createDirectories(addedFile.getParent());
        Files.writeString(
                addedFile,
                probeSource(addedRelativePath),
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW);

        NexusApplication.IndexOperation deltaIndex = application.index(project.id(), false, false);
        assertFalse(deltaIndex.report().fullSearchRebuild());
        assertEquals(2, deltaIndex.report().changedFiles());
        assertEquals(0, deltaIndex.report().removedFiles());
        assertEquals(fullIndex.report().scannedFiles() + 1, deltaIndex.report().scannedFiles());

        boolean probeSymbolFound = application.findSymbols(project.id(), PROBE_CLASS_NAME, 10).stream()
                .anyMatch(indexed -> PROBE_CLASS_NAME.equals(indexed.symbol().name()));
        assertTrue(probeSymbolFound, "Le symbole ajouté doit être visible après l'indexation incrémentale");

        NexusApplication.SearchOperation probeSearch = application.search(project.id(), PROBE_CLASS_NAME, 10, false);
        String normalizedAddedPath = normalizeRepositoryPath(addedRelativePath);
        boolean probeSearchFound = probeSearch.results().stream()
                .anyMatch(result -> normalizeRepositoryPath(result.candidate().path().toString())
                        .endsWith(normalizedAddedPath));
        assertTrue(probeSearchFound, "Le fichier ajouté doit être visible dans l'index de recherche dérivé");

        Files.writeString(
                modifiedFile,
                originalModifiedContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.delete(addedFile);

        NexusApplication.IndexOperation rollbackIndex = application.index(project.id(), false, false);
        assertFalse(rollbackIndex.report().fullSearchRebuild());
        assertEquals(1, rollbackIndex.report().changedFiles());
        assertEquals(1, rollbackIndex.report().removedFiles());
        assertEquals(fullIndex.report().scannedFiles(), rollbackIndex.report().scannedFiles());

        boolean probeSymbolStillPresent = application.findSymbols(project.id(), PROBE_CLASS_NAME, 10).stream()
                .anyMatch(indexed -> PROBE_CLASS_NAME.equals(indexed.symbol().name()));
        assertFalse(probeSymbolStillPresent, "Le symbole de probe doit disparaître après rollback");

        NexusApplication.SearchOperation rollbackSearch = application.search(project.id(), PROBE_CLASS_NAME, 10, false);
        boolean removedPathStillPresent = rollbackSearch.results().stream()
                .anyMatch(result -> normalizeRepositoryPath(result.candidate().path().toString())
                        .endsWith(normalizedAddedPath));
        assertFalse(removedPathStillPresent, "Le chemin supprimé doit disparaître de l'index de recherche");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("sourceRoot", sourceRoot.toString());
        report.put("controlledCopy", controlledCopy.toString());
        report.put("modifiedRelativePath", normalizeRepositoryPath(modifiedRelativePath));
        report.put("addedRelativePath", normalizedAddedPath);
        report.put("fullIndex", operationMetrics(fullIndex));
        report.put("incrementalNoChange", operationMetrics(noChangeIndex));
        report.put("incrementalSmallDelta", operationMetrics(deltaIndex));
        report.put("probeSearchMs", probeSearch.durationMs());
        report.put("probeSearchFound", probeSearchFound);
        report.put("rollback", operationMetrics(rollbackIndex));
        report.put("rollbackSearchMs", rollbackSearch.durationMs());
        report.put("rollbackRemovedPathAbsent", !removedPathStillPresent);

        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        System.out.printf(
                "NEXUS small-delta baseline: files=%d, full=%dms, noChange=%dms, delta=%dms, changed=%d, removed=%d, rollback=%dms%n",
                fullIndex.report().scannedFiles(),
                fullIndex.report().duration().toMillis(),
                noChangeIndex.report().duration().toMillis(),
                deltaIndex.report().duration().toMillis(),
                deltaIndex.report().changedFiles(),
                deltaIndex.report().removedFiles(),
                rollbackIndex.report().duration().toMillis());
        System.out.println("NEXUS small-delta report: " + output);
    }

    private static Map<String, Object> operationMetrics(NexusApplication.IndexOperation operation) {
        IndexingReport report = operation.report();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("durationMs", report.duration().toMillis());
        metrics.put("scannedFiles", report.scannedFiles());
        metrics.put("changedFiles", report.changedFiles());
        metrics.put("removedFiles", report.removedFiles());
        metrics.put("fullSearchRebuild", report.fullSearchRebuild());
        metrics.put("files", report.statistics().files());
        metrics.put("symbols", report.statistics().symbols());
        metrics.put("relations", report.statistics().relations());
        return metrics;
    }

    private static String requireRelativePath(String propertyName) {
        String value = System.getProperty(propertyName, "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Propriété obligatoire absente : " + propertyName);
        }
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException(propertyName + " doit être relatif au repository : " + value);
        }
        return normalizeRepositoryPath(value);
    }

    private static Path resolveInside(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Chemin hors de la copie contrôlée : " + relativePath);
        }
        return resolved;
    }

    private static void copyRepository(Path sourceRoot, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Path relative = sourceRoot.relativize(directory);
                if (!relative.toString().isEmpty() && containsExcludedDirectory(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = sourceRoot.relativize(file);
                if (!containsExcludedDirectory(relative)) {
                    Files.copy(file, targetRoot.resolve(relative));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean containsExcludedDirectory(Path relativePath) {
        for (Path element : relativePath) {
            if (COPY_EXCLUDED_DIRECTORIES.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String probeSource(String addedRelativePath) {
        String packageName = packageNameFromPath(addedRelativePath);
        StringBuilder source = new StringBuilder();
        if (!packageName.isBlank()) {
            source.append("package ").append(packageName).append(";\n\n");
        }
        source.append("public final class ").append(PROBE_CLASS_NAME).append(" {\n")
                .append("    private ").append(PROBE_CLASS_NAME).append("() {\n")
                .append("    }\n")
                .append("}\n");
        return source.toString();
    }

    private static String packageNameFromPath(String relativePath) {
        String normalized = normalizeRepositoryPath(relativePath);
        String marker = "src/main/java/";
        int markerIndex = normalized.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int packageStart = markerIndex + marker.length();
        int fileSeparator = normalized.lastIndexOf('/');
        if (fileSeparator <= packageStart) {
            return "";
        }
        return normalized.substring(packageStart, fileSeparator).replace('/', '.');
    }

    private static String normalizeRepositoryPath(String path) {
        return path.replace('\\', '/');
    }
}
