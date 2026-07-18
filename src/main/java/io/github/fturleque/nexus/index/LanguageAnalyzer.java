package io.github.fturleque.nexus.index;

import java.io.IOException;
import java.nio.file.Path;

public interface LanguageAnalyzer {

    boolean supports(Path file);

    AnalysisResult analyze(Path projectRoot, Path file) throws IOException;
}
