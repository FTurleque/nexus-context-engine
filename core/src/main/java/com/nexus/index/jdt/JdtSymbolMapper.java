package com.nexus.index.jdt;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.index.CodeSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.SnapshotLimits;
import static com.nexus.index.jdt.JdtLanguageServerCodeIntelligenceProvider.SOURCE_PROVIDER;

/** Composant interne du provider JDT LS. */
final class JdtSymbolMapper {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");

    private static final int LSP_CLASS = 5;
    private static final int LSP_METHOD = 6;
    private static final int LSP_CONSTRUCTOR = 9;
    private static final int LSP_ENUM = 10;
    private static final int LSP_INTERFACE = 11;
    private static final int LSP_STRUCT = 23;

    private static final System.Logger LOGGER = System.getLogger(JdtSymbolMapper.class.getName());
    private final SnapshotLimits snapshotLimits;
    JdtSymbolMapper(SnapshotLimits limits) { this.snapshotLimits = limits; }
    List<ProviderSymbol> parseDocumentSymbols(
            JsonNode response,
            String relativePath,
            String uri,
            String packageName,
            int sourceLineCount,
            int maxSymbols) throws IOException {
        List<ProviderSymbol> symbols = new ArrayList<>();
        DocumentSymbolContext context = new DocumentSymbolContext(
                relativePath, uri, packageName, sourceLineCount, maxSymbols);
        for (JsonNode node : arrayElements(response)) {
            flattenDocumentSymbol(node, context, List.of(), symbols);
        }
        return List.copyOf(symbols);
    }

    private void flattenDocumentSymbol(
            JsonNode node,
            DocumentSymbolContext context,
            List<String> ownerTypes,
            List<ProviderSymbol> output) throws IOException {
        ParsedDocumentSymbol parsed = parseDocumentSymbol(node, context);
        SymbolRange range = parsed.range();
        boolean namedSupportedSymbol = parsed.mappedKind().isPresent() && !parsed.name().isBlank();
        boolean validRange = isValidJdtRange(
                range.startLine(),
                range.endLine(),
                range.selectionLine(),
                context.sourceLineCount());

        List<String> childOwners = namedSupportedSymbol && validRange
                ? appendDocumentSymbol(parsed, context, ownerTypes, output)
                : ownersForSkippedDocumentSymbol(parsed, context, ownerTypes, namedSupportedSymbol);

        for (JsonNode child : arrayElements(node.path("children"))) {
            flattenDocumentSymbol(child, context, childOwners, output);
        }
    }

    private static ParsedDocumentSymbol parseDocumentSymbol(
            JsonNode node,
            DocumentSymbolContext context) {
        int lspKind = node.path("kind").asInt(-1);
        String name = node.path("name").asText("");
        String detail = node.path("detail").asText("");
        JsonNode rangeNode = node.has("range") ? node.path("range") : node.path("location").path("range");
        JsonNode selectionRangeNode = node.has("selectionRange") ? node.path("selectionRange") : rangeNode;
        String uri = node.path("location").path("uri").asText(context.fallbackUri());
        int startLine = rangeNode.path("start").path("line").asInt(-1);
        int endLine = rangeNode.path("end").path("line").asInt(startLine);
        int selectionLine = selectionRangeNode.path("start").path("line").asInt(startLine);
        int selectionCharacter = selectionRangeNode.path("start").path("character").asInt(0);
        return new ParsedDocumentSymbol(
                lspKind,
                name,
                detail,
                uri,
                new SymbolRange(startLine, endLine, selectionLine, selectionCharacter),
                symbolKind(lspKind));
    }

    private List<String> appendDocumentSymbol(
            ParsedDocumentSymbol parsed,
            DocumentSymbolContext context,
            List<String> ownerTypes,
            List<ProviderSymbol> output) throws IOException {
        if (output.size() >= context.maxSymbols()) {
            throw new IOException("JDT LS dépasse la limite de snapshot de " + snapshotLimits.maxSymbols() + " symboles");
        }

        SymbolKind kind = parsed.mappedKind().orElseThrow();
        String signature = parsed.detail().isBlank()
                ? parsed.name()
                : parsed.name() + " " + parsed.detail().trim();
        List<String> childOwners = ownerTypes;
        String qualifiedName;
        if (isType(kind)) {
            List<String> typeNames = appendOwner(ownerTypes, parsed.name());
            qualifiedName = qualifiedOwner(context.packageName(), typeNames);
            childOwners = typeNames;
        } else {
            qualifiedName = qualifiedOwner(context.packageName(), ownerTypes) + "#" + signature;
        }

        SymbolRange range = parsed.range();
        CodeSymbol symbol = new CodeSymbol(
                kind,
                parsed.name(),
                qualifiedName,
                signature,
                range.startLine() + 1,
                range.endLine() + 1,
                SOURCE_PROVIDER);
        output.add(new ProviderSymbol(
                context.relativePath(),
                parsed.uri(),
                symbol,
                parsed.lspKind(),
                range.startLine(),
                range.endLine(),
                range.selectionLine(),
                Math.max(0, range.selectionCharacter())));
        return childOwners;
    }

    private static List<String> ownersForSkippedDocumentSymbol(
            ParsedDocumentSymbol parsed,
            DocumentSymbolContext context,
            List<String> ownerTypes,
            boolean namedSupportedSymbol) {
        SymbolRange range = parsed.range();
        if (namedSupportedSymbol) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Ignoring inconsistent JDT symbol range for {0} in {1}: start={2}, end={3}, selection={4}, lines={5}",
                    parsed.name(),
                    context.relativePath(),
                    range.startLine(),
                    range.endLine(),
                    range.selectionLine(),
                    context.sourceLineCount());
        }
        if (isLspType(parsed.lspKind()) && !parsed.name().isBlank()) {
            return appendOwner(ownerTypes, parsed.name());
        }
        return ownerTypes;
    }

    private static List<String> appendOwner(List<String> ownerTypes, String name) {
        List<String> typeNames = new ArrayList<>(ownerTypes);
        typeNames.add(name);
        return List.copyOf(typeNames);
    }

    private record SymbolRange(
            int startLine,
            int endLine,
            int selectionLine,
            int selectionCharacter) {
    }

    private record ParsedDocumentSymbol(
            int lspKind,
            String name,
            String detail,
            String uri,
            SymbolRange range,
            Optional<SymbolKind> mappedKind) {
    }

    private record DocumentSymbolContext(
            String relativePath,
            String fallbackUri,
            String packageName,
            int sourceLineCount,
            int maxSymbols) {
    }

    private static boolean isValidJdtRange(
            int startLine,
            int endLine,
            int selectionLine,
            int sourceLineCount) {
        return sourceLineCount > 0
                && startLine >= 0
                && endLine >= startLine
                && endLine < sourceLineCount
                && selectionLine >= startLine
                && selectionLine <= endLine;
    }

    static int countSourceLines(String content) {
        long lineCount = content.lines().count();
        return lineCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) lineCount;
    }

    private static Optional<SymbolKind> symbolKind(int lspKind) {
        return switch (lspKind) {
            case LSP_CLASS -> Optional.of(SymbolKind.CLASS);
            case LSP_INTERFACE -> Optional.of(SymbolKind.INTERFACE);
            case LSP_STRUCT -> Optional.of(SymbolKind.RECORD);
            case LSP_ENUM -> Optional.of(SymbolKind.ENUM);
            case LSP_METHOD -> Optional.of(SymbolKind.METHOD);
            case LSP_CONSTRUCTOR -> Optional.of(SymbolKind.CONSTRUCTOR);
            default -> Optional.empty();
        };
    }

    private static boolean isLspType(int lspKind) {
        return lspKind == LSP_CLASS || lspKind == LSP_INTERFACE || lspKind == LSP_STRUCT || lspKind == LSP_ENUM;
    }

    static boolean isType(SymbolKind kind) {
        return switch (kind) {
            case CLASS, INTERFACE, RECORD, ENUM, ANNOTATION, TYPE -> true;
            case METHOD, CONSTRUCTOR -> false;
        };
    }

    static boolean isCallable(SymbolKind kind) {
        return kind == SymbolKind.METHOD || kind == SymbolKind.CONSTRUCTOR;
    }

    static boolean supportsImplementationQuery(SymbolKind kind) {
        return isType(kind) || kind == SymbolKind.METHOD;
    }

    static RelationKind hierarchyKind(int subtypeKind, int supertypeKind) {
        if (supertypeKind == LSP_INTERFACE && subtypeKind != LSP_INTERFACE) {
            return RelationKind.IMPLEMENTS;
        }
        return RelationKind.EXTENDS;
    }

    private static String qualifiedOwner(String packageName, List<String> ownerTypes) {
        String owner = ownerTypes.isEmpty() ? "<unknown>" : String.join(".", ownerTypes);
        return packageName.isBlank() ? owner : packageName + "." + owner;
    }

    static String packageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    static List<JsonNode> arrayElements(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(elements::add);
            return List.copyOf(elements);
        }
        return List.of(node);
    }

    record ProviderSymbol(
            String relativePath,
            String uri,
            CodeSymbol symbol,
            int lspKind,
            int startLineZeroBased,
            int endLineZeroBased,
            int selectionLineZeroBased,
            int selectionCharacter) {
    }

    record LocationRef(String relativePath, int lineZeroBased, int character) {
    }

}
