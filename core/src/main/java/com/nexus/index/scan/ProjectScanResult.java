package com.nexus.index.scan;

import com.nexus.index.ScannedFile;

import java.util.List;
import java.util.Objects;

/** Résultat observable d'un scan local avant analyse/indexation. */
public record ProjectScanResult(
        List<ScannedFile> files,
        int skippedFiles,
        List<String> diagnostics) {

    public ProjectScanResult {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (skippedFiles < 0) {
            throw new IllegalArgumentException("skippedFiles must not be negative");
        }
        files = List.copyOf(files);
        diagnostics = List.copyOf(diagnostics);
    }
}
