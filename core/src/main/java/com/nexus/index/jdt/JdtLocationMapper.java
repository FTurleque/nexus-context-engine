package com.nexus.index.jdt;

import com.nexus.paths.RepositoryPath;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nexus.index.jdt.JdtSymbolMapper.LocationRef;
import com.nexus.index.jdt.JdtSymbolMapper.ProviderSymbol;

/** Composant interne du provider JDT LS. */
final class JdtLocationMapper {
    static LocationRef locationFrom(JsonNode node, Path projectRoot) {
        String uri = node.path("uri").asText("");
        JsonNode range = node.path("range");
        if (uri.isBlank()) {
            uri = node.path("targetUri").asText("");
            range = node.has("targetSelectionRange") ? node.path("targetSelectionRange") : node.path("targetRange");
        }
        return locationFrom(uri, range, projectRoot);
    }

    static LocationRef hierarchyLocation(JsonNode node, Path projectRoot) {
        return locationFrom(node.path("uri").asText(""), node.path("selectionRange"), projectRoot);
    }

    static LocationRef locationFrom(String uri, JsonNode range, Path projectRoot) {
        if (uri.isBlank() || range == null || range.isMissingNode()) {
            return null;
        }
        try {
            URI parsedUri = URI.create(uri);
            if (!"file".equalsIgnoreCase(parsedUri.getScheme())) {
                return null;
            }
            Path absolutePath = Path.of(parsedUri);
            if (!absolutePath.isAbsolute() || !absolutePath.equals(absolutePath.normalize())
                    || !absolutePath.startsWith(projectRoot)) {
                return null;
            }
            String relativePath = RepositoryPath.encode(projectRoot.relativize(absolutePath));
            int line = range.path("start").path("line").asInt(-1);
            int character = range.path("start").path("character").asInt(0);
            if (line < 0) {
                return null;
            }
            return new LocationRef(relativePath, line, character);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static String hierarchyReference(
            Map<String, List<ProviderSymbol>> symbolsByPath,
            LocationRef location,
            String fallbackName) {
        String resolved = matchingSymbol(symbolsByPath, location)
                .map(symbol -> symbol.symbol().qualifiedName())
                .orElse(null);
        return resolved == null
                ? location.relativePath() + "#" + fallbackName
                : resolved;
    }

    static String referenceAt(
            Map<String, List<ProviderSymbol>> symbolsByPath,
            LocationRef location) {
        return matchingSymbol(symbolsByPath, location)
                .map(symbol -> symbol.symbol().qualifiedName())
                .orElse(location.relativePath() + ":" + (location.lineZeroBased() + 1));
    }

    static Optional<ProviderSymbol> matchingSymbol(
            Map<String, List<ProviderSymbol>> symbolsByPath,
            LocationRef location) {
        return symbolsByPath.getOrDefault(location.relativePath(), List.of()).stream()
                .filter(symbol -> location.lineZeroBased() >= symbol.startLineZeroBased())
                .filter(symbol -> location.lineZeroBased() <= symbol.endLineZeroBased())
                .min(Comparator.comparingInt(symbol -> symbol.endLineZeroBased() - symbol.startLineZeroBased()));
    }

}
