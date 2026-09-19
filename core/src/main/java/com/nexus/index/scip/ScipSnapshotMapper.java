package com.nexus.index.scip;

import com.nexus.paths.RepositoryPath;

import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.security.ProjectPathGuard;
import com.nexus.security.SafeFileIO;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nexus.index.scip.ScipPayload.ScipDocument;
import com.nexus.index.scip.ScipPayload.ScipOccurrence;
import com.nexus.index.scip.ScipPayload.ScipRelationship;
import com.nexus.index.scip.ScipPayload.ScipSymbolInformation;
import com.nexus.index.scip.ScipPayload.SourceRange;
import static com.nexus.index.scip.ScipCodeIndexImporter.SOURCE_PROVIDER;

/** Composant interne de lecture SCIP bornée. */
final class ScipSnapshotMapper {
    private static final int ROLE_DEFINITION = 0x1;
    private static final double SCIP_CONFIDENCE = 1.0d;
    private final ProjectPathGuard pathGuard;
    private final Set<String> scannedPaths;
    private final int maxSymbolFacts;
    private final int maxRelationFacts;
    private final int maxTotalFacts;
    private final Map<String, Integer> sourceLineCounts = new HashMap<>();
    private final List<IndexedSymbol> symbols = new java.util.ArrayList<>();
    private final List<IndexedRelation> relations = new java.util.ArrayList<>();
    private final Set<String> relationKeys = new java.util.LinkedHashSet<>();

    ScipSnapshotMapper(ProjectPathGuard pathGuard, Set<String> scannedPaths,
            int maxSymbolFacts, int maxRelationFacts, int maxTotalFacts) {
        this.pathGuard = pathGuard;
        this.scannedPaths = scannedPaths;
        this.maxSymbolFacts = maxSymbolFacts;
        this.maxRelationFacts = maxRelationFacts;
        this.maxTotalFacts = maxTotalFacts;
    }

    com.nexus.index.CodeIntelligenceSnapshot snapshot() {
        return new com.nexus.index.CodeIntelligenceSnapshot(SOURCE_PROVIDER, symbols, relations);
    }

    void importDocument(ScipDocument document) throws IOException {
        String relativePath = normalizeRelativePath(pathGuard, document.relativePath());
        if (scannedPaths != null && !scannedPaths.contains(relativePath)) {
            return;
        }
        int sourceLineCount = canonicalLineCount(pathGuard, relativePath, sourceLineCounts);

        // Index definitions once. The previous per-symbol linear scan made a dense
        // document O(symbols * occurrences).
        Map<String, ScipOccurrence> definitions = new HashMap<>();
        for (ScipOccurrence occurrence : document.occurrences()) {
            if (!occurrence.symbol().isBlank() && occurrence.range() != null && isDefinition(occurrence.roles())) {
                definitions.putIfAbsent(occurrence.symbol(), occurrence);
            }
        }

        for (ScipSymbolInformation symbolInformation : document.symbols()) {
            ScipOccurrence definition = definitions.get(symbolInformation.symbol());
            SymbolKind symbolKind = mapKind(symbolInformation.kind());
            if (definition != null && symbolKind != null) {
                SourceRange validatedRange = validateDefinitionRange(
                        relativePath,
                        definition.range(),
                        sourceLineCount);
                String name = symbolName(symbolInformation);
                ensureSymbolCapacity(symbols.size(), relations.size(), maxSymbolFacts, maxTotalFacts);
                symbols.add(new IndexedSymbol(
                        relativePath,
                        new CodeSymbol(
                                symbolKind,
                                name,
                                symbolInformation.symbol(),
                                symbolInformation.signature().isBlank() ? name : symbolInformation.signature(),
                                validatedRange.startLine() + 1,
                                validatedRange.endLine() + 1,
                                SOURCE_PROVIDER)));
            }

            for (ScipRelationship relationship : symbolInformation.relationships()) {
                if (relationship.symbol().isBlank()) {
                    continue;
                }
                if (relationship.implementation()) {
                    addRelation(
                            relativePath,
                            RelationKind.IMPLEMENTS,
                            symbolInformation.symbol(),
                            relationship.symbol());
                }
                if (relationship.reference()) {
                    addRelation(
                            relativePath,
                            RelationKind.REFERENCES,
                            symbolInformation.symbol(),
                            relationship.symbol());
                }
                if (relationship.typeDefinition()) {
                    addRelation(
                            relativePath,
                            RelationKind.TYPE_DEFINITION,
                            symbolInformation.symbol(),
                            relationship.symbol());
                }
                if (relationship.definition()) {
                    addRelation(
                            relativePath,
                            RelationKind.DEFINITION_OF,
                            symbolInformation.symbol(),
                            relationship.symbol());
                }
            }
        }

        for (ScipOccurrence occurrence : document.occurrences()) {
            if (occurrence.symbol().isBlank() || isDefinition(occurrence.roles())) {
                continue;
            }
            addRelation(
                    relativePath,
                    RelationKind.REFERENCES,
                    relativePath,
                    occurrence.symbol());
        }
    }

    private static SourceRange validateDefinitionRange(
            String relativePath,
            SourceRange range,
            int sourceLineCount) throws IOException {
        if (range.startLine() < 0 || range.endLine() < range.startLine()) {
            throw new IOException("SCIP contains an invalid symbol line range for '"
                    + relativePath + "': " + range.startLine() + "-" + range.endLine());
        }
        if (range.startLine() == Integer.MAX_VALUE || range.endLine() == Integer.MAX_VALUE) {
            throw new IOException("SCIP symbol line range overflows one-based coordinates for '"
                    + relativePath + "'");
        }
        int startLine = range.startLine() + 1;
        int endLine = range.endLine() + 1;
        if (!CodeSymbol.isWithinLineCount(startLine, endLine, sourceLineCount)) {
            throw new IOException("SCIP symbol line range exceeds canonical file '"
                    + relativePath + "': " + startLine + "-" + endLine
                    + " for " + sourceLineCount + " line(s)");
        }
        return range;
    }

    private static int canonicalLineCount(
            ProjectPathGuard pathGuard,
            String relativePath,
            Map<String, Integer> sourceLineCounts) throws IOException {
        Integer cached = sourceLineCounts.get(relativePath);
        if (cached != null) {
            return cached;
        }
        Path source = pathGuard.requireRegularFile(pathGuard.resolve(new RepositoryPath(relativePath)));
        long lineCount = SafeFileIO.readStringNoFollow(source).lines().count();
        if (lineCount > Integer.MAX_VALUE) {
            throw new IOException("Source file contains too many lines to validate SCIP symbol ranges: "
                    + relativePath);
        }
        int result = (int) lineCount;
        sourceLineCounts.put(relativePath, result);
        return result;
    }

    private void addRelation(String relativePath, RelationKind kind, String source, String target) throws IOException {
        if (source.isBlank() || target.isBlank()) {
            return;
        }
        String key = relativePath + '\u0000' + kind + '\u0000' + source + '\u0000' + target;
        if (relationKeys.contains(key)) {
            return;
        }
        ensureRelationCapacity(symbols.size(), relations.size(), maxRelationFacts, maxTotalFacts);
        relationKeys.add(key);
        relations.add(new IndexedRelation(
                relativePath,
                new SymbolRelation(kind, source, target, SCIP_CONFIDENCE, SOURCE_PROVIDER)));
    }

    private static void ensureSymbolCapacity(
            int symbolCount,
            int relationCount,
            int maxSymbolFacts,
            int maxTotalFacts) throws IOException {
        if (symbolCount >= maxSymbolFacts) {
            throw new IOException("SCIP dépasse la limite de " + maxSymbolFacts + " faits symbole");
        }
        ensureTotalCapacity(symbolCount, relationCount, maxTotalFacts);
    }

    private static void ensureRelationCapacity(
            int symbolCount,
            int relationCount,
            int maxRelationFacts,
            int maxTotalFacts) throws IOException {
        if (relationCount >= maxRelationFacts) {
            throw new IOException("SCIP dépasse la limite de " + maxRelationFacts + " faits relation");
        }
        ensureTotalCapacity(symbolCount, relationCount, maxTotalFacts);
    }

    private static void ensureTotalCapacity(int symbolCount, int relationCount, int maxTotalFacts) throws IOException {
        if ((long) symbolCount + relationCount >= maxTotalFacts) {
            throw new IOException("SCIP dépasse la limite de " + maxTotalFacts + " faits totaux");
        }
    }

    private static boolean isDefinition(int roles) {
        return (roles & ROLE_DEFINITION) != 0;
    }

    private static String normalizeRelativePath(ProjectPathGuard pathGuard, String relativePath) throws IOException {
        try {
            var path = RepositoryPath.fromProvider(relativePath);
            pathGuard.resolve(path);
            return path.value();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid SCIP repository path", exception);
        }
    }

    private static String symbolName(ScipSymbolInformation symbolInformation) {
        if (!symbolInformation.displayName().isBlank()) {
            return symbolInformation.displayName();
        }
        String symbol = symbolInformation.symbol().trim();
        if (symbol.isBlank()) {
            return "<unknown>";
        }
        if (symbol.endsWith("().")) {
            symbol = symbol.substring(0, symbol.length() - 3);
        }
        while (!symbol.isEmpty() && isDescriptorSuffix(symbol.charAt(symbol.length() - 1))) {
            symbol = symbol.substring(0, symbol.length() - 1);
        }
        int separator = -1;
        for (char candidate : new char[]{'/', '#', '.', ':', '!', ' '}) {
            separator = Math.max(separator, symbol.lastIndexOf(candidate));
        }
        return separator >= 0 && separator + 1 < symbol.length()
                ? symbol.substring(separator + 1)
                : symbol;
    }

    private static boolean isDescriptorSuffix(char value) {
        return value == '/' || value == '#' || value == '.' || value == ':' || value == '!';
    }

    private static SymbolKind mapKind(int scipKind) {
        return switch (scipKind) {
            case 7 -> SymbolKind.CLASS;
            case 9 -> SymbolKind.CONSTRUCTOR;
            case 11 -> SymbolKind.ENUM;
            case 21, 42, 53, 56 -> SymbolKind.INTERFACE;
            case 17, 18, 26, 66, 67, 68, 69, 70, 71, 74, 76, 80 -> SymbolKind.METHOD;
            default -> null;
        };
    }

}
