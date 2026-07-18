package io.github.fturleque.nexus.index;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AnalysisResult(
        Path file,
        String language,
        List<CodeSymbol> symbols,
        List<SymbolRelation> relations) {

    public AnalysisResult {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(relations, "relations");
        symbols = List.copyOf(symbols);
        relations = List.copyOf(relations);
    }
}
