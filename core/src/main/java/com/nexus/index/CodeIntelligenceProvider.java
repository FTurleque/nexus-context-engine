package com.nexus.index;

import java.io.IOException;
import java.nio.file.Path;

public interface CodeIntelligenceProvider {

    String sourceProvider();

    CodeIntelligenceSnapshot analyze(Path projectRoot) throws IOException;
}
