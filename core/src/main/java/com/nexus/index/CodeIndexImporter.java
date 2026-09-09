package com.nexus.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface CodeIndexImporter {

    String sourceProvider();

    Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot) throws IOException;
    /** Portée canonique effectivement scannée ; les importers peuvent filtrer avant toute lecture. */
    default Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot, java.util.Set<String> scannedPaths) throws IOException {
        return importIndex(projectRoot);
    }
}
