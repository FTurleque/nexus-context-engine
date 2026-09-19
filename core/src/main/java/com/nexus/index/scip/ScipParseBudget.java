package com.nexus.index.scip;

import java.io.IOException;

import com.nexus.index.scip.ScipCodeIndexImporter.ParseLimits;

/** Composant interne de lecture SCIP bornée. */
final class ScipParseBudget {

    private final ParseLimits limits;
    private int documents;
    private int occurrences;
    private int symbolInfos;
    private int relationships;

    ScipParseBudget(ParseLimits limits) {
        this.limits = limits;
    }

    void document() throws IOException {
        documents = increment(documents, limits.maxDocuments(), "documents SCIP");
    }

    void occurrence() throws IOException {
        occurrences = increment(occurrences, limits.maxOccurrences(), "occurrences SCIP");
    }

    void symbolInfo() throws IOException {
        symbolInfos = increment(symbolInfos, limits.maxSymbolInfos(), "symbol infos SCIP");
    }

    void relationship() throws IOException {
        relationships = increment(relationships, limits.maxRelationships(), "relationships SCIP");
    }

    private static int increment(int current, int maximum, String label) throws IOException {
        if (current >= maximum) {
            throw new IOException("SCIP dépasse la limite de " + maximum + " " + label);
        }
        return current + 1;
    }}
