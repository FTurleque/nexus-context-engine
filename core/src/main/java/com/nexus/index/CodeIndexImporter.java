package com.nexus.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface CodeIndexImporter {

    String sourceProvider();

    Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot) throws IOException;
}
