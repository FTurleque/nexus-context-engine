package com.nexus.search;

import com.nexus.index.CodeSymbol;

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
        signals.forEach((signal, value) -> {
            Objects.requireNonNull(signal, "signal name");
            Objects.requireNonNull(value, "signal value");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Search signal '" + signal + "' must be finite: " + value);
            }
        });
        signals = Map.copyOf(signals);
    }
}
