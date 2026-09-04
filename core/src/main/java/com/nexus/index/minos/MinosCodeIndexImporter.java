package com.nexus.index.minos;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.ExternalSymbolIdentity;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.security.ProjectPathGuard;
import com.nexus.security.SafeFileIO;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adaptateur Java 21 pour l'export JSON versionné produit par MINOS.
 *
 * <p>Le chemin applicatif doit fournir la liste canonique des fichiers déjà
 * indexés par NEXUS. L'ancienne surcharge à deux arguments reste disponible pour
 * les outils/tests autonomes mais route désormais sa découverte par le scanner
 * projet borné de NEXUS.</p>
 *
 * <p>Le document est parsé en streaming : NEXUS ne matérialise jamais l'arbre
 * JSON complet en mémoire. Les faits symboles/relations sont lus et validés un
 * par un, avec des plafonds explicites en plus de la limite de transport.</p>
 */
public final class MinosCodeIndexImporter {

    public static final String SOURCE_PROVIDER = "minos";
    public static final long MAX_EXPORT_BYTES = 128L * 1024L * 1024L;
    public static final int MAX_SYMBOL_FACTS = 500_000;
    public static final int MAX_RELATION_FACTS = 500_000;

    private static final String CONTRACT_VERSION = "1";
    private static final String PRODUCER = "MINOS";
    private static final int READER_BUFFER_CHARS = 16 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Compatibilité autonome. La façade NexusApplication utilise la surcharge
     * avec fichiers canoniques. Cette surcharge est conservée mais sa découverte
     * physique respecte désormais les limites de {@link ProjectScanner}.
     */
    @Deprecated(forRemoval = false)
    public CodeIntelligenceSnapshot importPayload(Path projectRoot, String payload) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        Set<String> scannedProjectFiles = new LinkedHashSet<>();
        for (var scannedFile : new ProjectScanner().scan(root)) {
            scannedProjectFiles.add(scannedFile.relativePath());
        }
        return importPayload(root, scannedProjectFiles, payload);
    }

    public CodeIntelligenceSnapshot importPayload(
            Path projectRoot,
            Set<String> indexedProjectFiles,
            String payload) throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot").toRealPath();
        ProjectPathGuard pathGuard = new ProjectPathGuard(root);
        Set<String> safeProjectFiles = canonicalIndexedFiles(indexedProjectFiles);
        String documentPayload = Objects.requireNonNull(payload, "payload");
        requireTransportSize(documentPayload);

        try (JsonParser parser = objectMapper.createParser(documentPayload)) {
            return parseDocument(root, pathGuard, safeProjectFiles, parser);
        }
    }

    /**
     * Lit un payload UTF-8 borné sans conserver en parallèle un byte[] de la taille
     * complète du document. Cette primitive est destinée notamment à la CLI stdin.
     */
    public static String readPayload(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        Reader reader = new InputStreamReader(new BoundedPayloadInputStream(input), StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder(READER_BUFFER_CHARS);
        char[] buffer = new char[READER_BUFFER_CHARS];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            if (read > 0) {
                output.append(buffer, 0, read);
            }
        }
        return output.toString();
    }

    private CodeIntelligenceSnapshot parseDocument(
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

    private int readSymbols(
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

    private int readRelations(
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

    private static Path readProjectRoot(JsonParser parser, JsonToken valueToken) throws IOException {
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

    private static String requiredParserText(JsonParser parser, JsonToken valueToken, String field) throws IOException {
        if (valueToken != JsonToken.VALUE_STRING) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        String value = parser.getValueAsString().trim();
        if (value.isBlank()) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        return value;
    }

    private static void requireExpectedValue(String field, String value, String expected) throws IOException {
        if (value == null) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        if (!expected.equals(value)) {
            throw new IOException(
                    "unsupported MINOS export " + field + ": " + value + " (expected " + expected + ")");
        }
    }

    private static void requireTransportSize(String payload) throws IOException {
        long bytes = 0L;
        for (int index = 0; index < payload.length(); index++) {
            char character = payload.charAt(index);
            if (character <= 0x7F) {
                bytes += 1L;
            } else if (character <= 0x7FF) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < payload.length()
                    && Character.isLowSurrogate(payload.charAt(index + 1))) {
                bytes += 4L;
                index++;
            } else {
                // Conservative for non-BMP-invalid/unpaired UTF-16 input: never undercount.
                bytes += 3L;
            }
            if (bytes > MAX_EXPORT_BYTES) {
                throw transportTooLarge();
            }
        }
    }

    private static IOException transportTooLarge() {
        return new IOException("MINOS export exceeds the 128 MiB transport limit");
    }

    private static IndexedSymbol mapSymbol(
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
        String relativePath = safeRelativePath(safeProjectFiles, requiredText(node, "filePath"));
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

    private static IndexedRelation mapRelation(Set<String> safeProjectFiles, JsonNode node) throws IOException {
        if (!"RESOLVED".equals(optionalText(node, "resolutionStatus"))) {
            return null;
        }
        RelationKind kind = mapRelationKind(optionalText(node, "kind"));
        if (kind == null) {
            return null;
        }
        String relativePath = safeRelativePath(safeProjectFiles, requiredText(node, "filePath"));
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

    private static int canonicalLineCount(
            ProjectPathGuard pathGuard,
            String relativePath,
            Map<String, Integer> sourceLineCounts) throws IOException {
        Integer cached = sourceLineCounts.get(relativePath);
        if (cached != null) {
            return cached;
        }
        Path source = pathGuard.requireRegularFile(pathGuard.resolve(Path.of(relativePath)));
        int lineCount = countLines(SafeFileIO.readStringNoFollow(source));
        sourceLineCounts.put(relativePath, lineCount);
        return lineCount;
    }

    private static int countLines(String content) throws IOException {
        long lineCount = content.lines().count();
        if (lineCount > Integer.MAX_VALUE) {
            throw new IOException("Source file contains too many lines to validate a MINOS symbol range");
        }
        return (int) lineCount;
    }

    private static SymbolKind mapSymbolKind(String value) {
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

    private static RelationKind mapRelationKind(String value) {
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

    private static double confidence(JsonNode node) throws IOException {
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

    private static Set<String> canonicalIndexedFiles(Set<String> indexedProjectFiles) throws IOException {
        Objects.requireNonNull(indexedProjectFiles, "indexedProjectFiles");
        Set<String> safe = new LinkedHashSet<>();
        for (String value : indexedProjectFiles) {
            if (value == null || value.isBlank()) {
                throw new IOException("NEXUS canonical indexed file path must not be blank");
            }
            String normalized = safeRelativePathSyntax(value);
            if (normalized == null) {
                throw new IOException("NEXUS canonical indexed file path is invalid: " + value);
            }
            safe.add(normalized);
        }
        return Set.copyOf(safe);
    }

    private static String safeRelativePath(Set<String> safeProjectFiles, String exportedPath) {
        String normalized = safeRelativePathSyntax(exportedPath);
        return normalized != null && safeProjectFiles.contains(normalized) ? normalized : null;
    }

    private static String safeRelativePathSyntax(String exportedPath) {
        try {
            Path raw = Path.of(exportedPath);
            if (raw.isAbsolute()) {
                return null;
            }
            for (Path segment : raw) {
                if ("..".equals(segment.toString())) {
                    return null;
                }
            }
            Path normalized = raw.normalize();
            if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
                return null;
            }
            return normalized.toString().replace('\\', '/');
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static Path normalizedAbsolutePath(String value, String field) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw new IOException("MINOS export " + field + " must be canonical and absolute");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IOException("MINOS export contains an invalid " + field, exception);
        }
    }

    private static String requiredText(JsonNode parent, String field) throws IOException {
        String value = optionalText(parent, field);
        if (value == null) {
            throw new IOException("MINOS export field '" + field + "' must not be blank");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isBlank() ? null : value;
    }

    private static int positiveLine(JsonNode parent, String field) throws IOException {
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

    private static final class BoundedPayloadInputStream extends FilterInputStream {

        private long consumed;

        private BoundedPayloadInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                record(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            long remaining = MAX_EXPORT_BYTES - consumed;
            int requested = (int) Math.min((long) length, remaining + 1L);
            int read = super.read(buffer, offset, requested);
            if (read > 0) {
                record(read);
            }
            return read;
        }

        private void record(long bytes) throws IOException {
            if (bytes > MAX_EXPORT_BYTES - consumed) {
                throw transportTooLarge();
            }
            consumed += bytes;
        }
    }
}
