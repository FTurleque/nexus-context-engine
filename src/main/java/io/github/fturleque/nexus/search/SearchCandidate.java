package io.github.fturleque.nexus.search;

import io.github.fturleque.nexus.index.CodeSymbol;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record SearchCandidate(
        String id,
        CandidateType type,
        Path path,
        CodeSymbol symbol,
        String excerpt,
        Map<String, Double> signals) {

    public SearchCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(signals, "signals");
        signals = Map.copyOf(signals);
    }
}
