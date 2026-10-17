package com.nexus.index.scip;

import java.util.List;

/** Composant interne de lecture SCIP bornée. */
final class ScipPayload {
    record ScipDocument(
            String relativePath,
            List<ScipOccurrence> occurrences,
            List<ScipSymbolInformation> symbols) {
    }

    record ScipOccurrence(String symbol, int roles, SourceRange range) {
    }

    record ScipSymbolInformation(
            String symbol,
            int kind,
            String displayName,
            String signature,
            List<ScipRelationship> relationships) {
    }

    record ScipRelationship(
            String symbol,
            boolean reference,
            boolean implementation,
            boolean typeDefinition,
            boolean definition) {
    }

    record SourceRange(int startLine, int endLine) {
    }

}
