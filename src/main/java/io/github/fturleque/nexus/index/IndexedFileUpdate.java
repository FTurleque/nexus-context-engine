package io.github.fturleque.nexus.index;

import java.util.Objects;

public record IndexedFileUpdate(ScannedFile file, AnalysisResult analysis) {

    public IndexedFileUpdate {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(analysis, "analysis");
    }
}
