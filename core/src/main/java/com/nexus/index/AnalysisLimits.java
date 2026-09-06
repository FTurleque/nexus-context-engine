package com.nexus.index;

/**
 * Bornes de cardinalité pour l'analyse locale d'un fichier source.
 *
 * <p>Les limites sont appliquées pendant l'extraction des faits afin d'éviter
 * de matérialiser des collections disproportionnées avant l'écriture SQLite/Lucene.</p>
 */
public record AnalysisLimits(int maxSymbolsPerFile, int maxRelationsPerFile) {

    public static final int DEFAULT_MAX_SYMBOLS_PER_FILE = 20_000;
    public static final int DEFAULT_MAX_RELATIONS_PER_FILE = 10_000;

    public AnalysisLimits {
        if (maxSymbolsPerFile <= 0) {
            throw new IllegalArgumentException("maxSymbolsPerFile must be greater than zero");
        }
        if (maxRelationsPerFile <= 0) {
            throw new IllegalArgumentException("maxRelationsPerFile must be greater than zero");
        }
    }

    public static AnalysisLimits defaults() {
        return new AnalysisLimits(DEFAULT_MAX_SYMBOLS_PER_FILE, DEFAULT_MAX_RELATIONS_PER_FILE);
    }
}
