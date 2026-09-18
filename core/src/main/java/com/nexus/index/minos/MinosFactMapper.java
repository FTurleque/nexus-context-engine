package com.nexus.index.minos;

import com.nexus.paths.RepositoryPath;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.nexus.index.minos.MinosCodeIndexImporter.*;
import static com.nexus.index.minos.MinosPathPolicy.*;

/** Composant interne du contrat d’import MINOS. */
final class MinosFactMapper {
    private static final String FILE_PATH = "filePath";
    static IndexedSymbol mapSymbol(
            ProjectPathGuard pathGuard,
            Set<String> safeProjectFiles,
            Map<String, Integer> sourceLineCounts,
            JsonNode node) throws IOException {
        if (!"RESOLVED".equals(optionalText(node, "resolutionStatus"))) {
            return null;
        }
        SymbolKind kind = mapSymbolKind(optionalText(node, "kind"));
        if (kind == null) {
            return null;
        }
        String relativePath = safeRelativePath(safeProjectFiles, node.path(FILE_PATH).isTextual() ? node.path(FILE_PATH).textValue() : null);
        if (relativePath == null) {
            return null;
        }
        int startLine = positiveLine(node, "startLine");
        int endLine = positiveLine(node, "endLine");
        if (endLine < startLine) {
            throw new IOException("MINOS export contains an invalid symbol line range");
        }
        int sourceLineCount = canonicalLineCount(pathGuard, relativePath, sourceLineCounts);
        if (!CodeSymbol.isWithinLineCount(startLine, endLine, sourceLineCount)) {
            throw new IOException("MINOS export symbol line range exceeds canonical file '"
                    + relativePath + "': " + startLine + "-" + endLine
                    + " for " + sourceLineCount + " line(s)");
        }
        String name = requiredText(node, "name");
        String qualifiedName = optionalText(node, "qualifiedName");
        if (qualifiedName == null) {
            qualifiedName = name;
        }
        String signature = optionalText(node, "signature");
        if (signature == null) {
            signature = "";
        }
        return new IndexedSymbol(
                relativePath,
                new CodeSymbol(
                        kind,
                        name,
                        qualifiedName,
                        signature,
                        startLine,
                        endLine,
                        SOURCE_PROVIDER));
    }

    static IndexedRelation mapRelation(Set<String> safeProjectFiles, JsonNode node) throws IOException {
        if (!"RESOLVED".equals(optionalText(node, "resolutionStatus"))) {
            return null;
        }
        RelationKind kind = mapRelationKind(optionalText(node, "kind"));
        if (kind == null) {
            return null;
        }
        String relativePath = safeRelativePath(safeProjectFiles, node.path(FILE_PATH).isTextual() ? node.path(FILE_PATH).textValue() : null);
        if (relativePath == null) {
            return null;
        }
        String source = requiredText(node, "sourceQualifiedName");
        String target = requiredText(node, "targetQualifiedName");
        double confidence = confidence(node);
        return new IndexedRelation(
                relativePath,
                new SymbolRelation(kind, source, target, confidence, SOURCE_PROVIDER));
    }

    static int canonicalLineCount(
            ProjectPathGuard pathGuard,
            String relativePath,
            Map<String, Integer> sourceLineCounts) throws IOException {
        Integer cached = sourceLineCounts.get(relativePath);
        if (cached != null) {
            return cached;
        }
        Path source = pathGuard.requireRegularFile(pathGuard.resolve(new RepositoryPath(relativePath)));
        int lineCount = countLines(SafeFileIO.readStringNoFollow(source));
        sourceLineCounts.put(relativePath, lineCount);
        return lineCount;
    }

    static int countLines(String content) throws IOException {
        long lineCount = content.lines().count();
        if (lineCount > Integer.MAX_VALUE) {
            throw new IOException("Source file contains too many lines to validate a MINOS symbol range");
        }
        return (int) lineCount;
    }

    static SymbolKind mapSymbolKind(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "CLASS" -> SymbolKind.CLASS;
            case "INTERFACE", "TRAIT" -> SymbolKind.INTERFACE;
            case "RECORD" -> SymbolKind.RECORD;
            case "ENUM" -> SymbolKind.ENUM;
            case "ANNOTATION" -> SymbolKind.ANNOTATION;
            case "METHOD", "FUNCTION" -> SymbolKind.METHOD;
            case "CONSTRUCTOR" -> SymbolKind.CONSTRUCTOR;
            case "TYPE", "STRUCT", "TYPE_ALIAS" -> SymbolKind.TYPE;
            default -> null;
        };
    }

    static RelationKind mapRelationKind(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "IMPORTS" -> RelationKind.IMPORTS;
            case "EXTENDS" -> RelationKind.EXTENDS;
            case "IMPLEMENTS" -> RelationKind.IMPLEMENTS;
            case "CALLS" -> RelationKind.CALLS;
            case "REFERENCES" -> RelationKind.REFERENCES;
            case "TYPE_DEFINITION" -> RelationKind.TYPE_DEFINITION;
            case "DEFINITION" -> RelationKind.DEFINITION_OF;
            default -> null;
        };
    }

    static double confidence(JsonNode node) throws IOException {
        JsonNode confidence = node.get("confidence");
        if (confidence != null && !confidence.isNull()) {
            if (!confidence.isNumber()) {
                throw new IOException("MINOS export contains a non-numeric relation confidence");
            }
            double value = confidence.asDouble();
            if (Double.isFinite(value) && value >= 0.0d && value <= 1.0d) {
                return value;
            }
            throw new IOException("MINOS export contains an invalid relation confidence");
        }
        if ("FACTUAL".equals(optionalText(node, "nature"))) {
            return 1.0d;
        }
        throw new IOException("MINOS derived relation is missing confidence");
    }

    static String requiredText(JsonNode parent, String field) throws IOException {
        String value = optionalText(parent, field);
        if (value == null) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        return value;
    }

    static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isBlank() ? null : value;
    }

    static int positiveLine(JsonNode parent, String field) throws IOException {
        JsonNode node = parent.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw new IOException("MINOS export field '" + field + "' must be an integer");
        }
        int value = node.asInt();
        if (value < 1) {
            throw new IOException("MINOS export field '" + field + "' must be positive");
        }
        return value;
    }
}
