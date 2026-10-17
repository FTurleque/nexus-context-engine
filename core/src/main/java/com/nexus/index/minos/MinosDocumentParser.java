package com.nexus.index.minos;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.ExternalSymbolIdentity;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.security.ProjectPathGuard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.nexus.index.minos.MinosCodeIndexImporter.*;
import static com.nexus.index.minos.MinosPathPolicy.*;
import static com.nexus.index.minos.MinosFactMapper.*;

/** Composant interne du contrat d’import MINOS. */
final class MinosDocumentParser {
    private final ObjectMapper objectMapper;
    MinosDocumentParser(ObjectMapper mapper) { this.objectMapper = mapper; }
    private static final String CONTRACT_VERSION = "1";
    private static final String PRODUCER = "MINOS";
    CodeIntelligenceSnapshot parseDocument(
            Path root,
            ProjectPathGuard pathGuard,
            Set<String> safeProjectFiles,
            JsonParser parser) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IOException("MINOS export root must be a JSON object");
        }

        String contractVersion = null;
        String producer = null;
        Path exportedRoot = null;
        boolean symbolsSeen = false;
        boolean relationsSeen = false;
        int symbolFacts = 0;
        int relationFacts = 0;
        Map<String, Integer> sourceLineCounts = new LinkedHashMap<>();
        Map<ExternalSymbolIdentity, IndexedSymbol> symbols = new LinkedHashMap<>();
        List<IndexedRelation> relations = new ArrayList<>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw new IOException("MINOS export root contains malformed JSON fields");
            }
            String field = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            switch (field) {
                case "contractVersion" -> contractVersion = requiredParserText(parser, valueToken, field);
                case "producer" -> producer = requiredParserText(parser, valueToken, field);
                case "project" -> exportedRoot = readProjectRoot(parser, valueToken);
                case "symbols" -> {
                    symbolsSeen = true;
                    symbolFacts = readSymbols(
                            parser,
                            valueToken,
                            pathGuard,
                            safeProjectFiles,
                            sourceLineCounts,
                            symbols,
                            symbolFacts);
                }
                case "relations" -> {
                    relationsSeen = true;
                    relationFacts = readRelations(
                            parser,
                            valueToken,
                            safeProjectFiles,
                            relations,
                            relationFacts);
                }
                default -> parser.skipChildren();
            }
        }
        if (parser.nextToken() != null) {
            throw new IOException("MINOS export contains trailing JSON content");
        }

        requireExpectedValue("contractVersion", contractVersion, CONTRACT_VERSION);
        requireExpectedValue("producer", producer, PRODUCER);
        if (exportedRoot == null) {
            throw new IOException("MINOS export field 'project' must be an object with a rootPath");
        }
        if (!root.equals(exportedRoot)) {
            throw new IOException("MINOS export belongs to another project root: " + exportedRoot);
        }
        if (!symbolsSeen) {
            throw new IOException("MINOS export field 'symbols' must be an array");
        }
        if (!relationsSeen) {
            throw new IOException("MINOS export field 'relations' must be an array");
        }

        return new CodeIntelligenceSnapshot(
                SOURCE_PROVIDER,
                List.copyOf(symbols.values()),
                relations);
    }

    int readSymbols(
            JsonParser parser,
            JsonToken valueToken,
            ProjectPathGuard pathGuard,
            Set<String> safeProjectFiles,
            Map<String, Integer> sourceLineCounts,
            Map<ExternalSymbolIdentity, IndexedSymbol> symbols,
            int factsRead) throws IOException {
        if (valueToken != JsonToken.START_ARRAY) {
            throw new IOException("MINOS export field 'symbols' must be an array");
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            factsRead++;
            if (factsRead > MAX_SYMBOL_FACTS) {
                throw new IOException("MINOS export exceeds the symbol fact limit of " + MAX_SYMBOL_FACTS);
            }
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new IOException("MINOS export symbols must be JSON objects");
            }
            JsonNode symbolNode = objectMapper.readTree(parser);
            IndexedSymbol symbol = mapSymbol(pathGuard, safeProjectFiles, sourceLineCounts, symbolNode);
            if (symbol != null) {
                symbols.putIfAbsent(ExternalSymbolIdentity.of(symbol), symbol);
            }
        }
        return factsRead;
    }

    int readRelations(
            JsonParser parser,
            JsonToken valueToken,
            Set<String> safeProjectFiles,
            List<IndexedRelation> relations,
            int factsRead) throws IOException {
        if (valueToken != JsonToken.START_ARRAY) {
            throw new IOException("MINOS export field 'relations' must be an array");
        }
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            factsRead++;
            if (factsRead > MAX_RELATION_FACTS) {
                throw new IOException("MINOS export exceeds the relation fact limit of " + MAX_RELATION_FACTS);
            }
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new IOException("MINOS export relations must be JSON objects");
            }
            JsonNode relationNode = objectMapper.readTree(parser);
            IndexedRelation relation = mapRelation(safeProjectFiles, relationNode);
            if (relation != null) {
                relations.add(relation);
            }
        }
        return factsRead;
    }

    static Path readProjectRoot(JsonParser parser, JsonToken valueToken) throws IOException {
        if (valueToken != JsonToken.START_OBJECT) {
            throw new IOException("MINOS export field 'project' must be an object");
        }
        String rootPath = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw new IOException("MINOS export project contains malformed JSON fields");
            }
            String field = parser.currentName();
            JsonToken projectValue = parser.nextToken();
            if ("rootPath".equals(field)) {
                rootPath = requiredParserText(parser, projectValue, "rootPath");
            } else {
                parser.skipChildren();
            }
        }
        if (rootPath == null) {
            throw new IOException("MINOS export field 'rootPath' must not be blank");
        }
        return normalizedAbsolutePath(rootPath, "project root");
    }

    static String requiredParserText(JsonParser parser, JsonToken valueToken, String field) throws IOException {
        if (valueToken != JsonToken.VALUE_STRING) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        String value = parser.getValueAsString().trim();
        if (value.isBlank()) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        return value;
    }

    static void requireExpectedValue(String field, String value, String expected) throws IOException {
        if (value == null) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        if (!expected.equals(value)) {
            throw new IOException(
                    "unsupported MINOS export " + field + ": " + value + " (expected " + expected + ")");
        }
    }
}
