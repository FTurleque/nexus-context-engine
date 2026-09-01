package com.nexus.index.scip;

import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.SymbolRelation;
import com.nexus.security.ProjectPathGuard;
import com.nexus.security.SafeFileIO;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Imports the subset of SCIP needed by NEXUS without coupling the domain model
 * to generated Protobuf classes. Unknown SCIP fields are deliberately skipped.
 */
public final class ScipCodeIndexImporter implements CodeIndexImporter {

    public static final String SOURCE_PROVIDER = "scip";
    public static final String DEFAULT_INDEX_FILE = "index.scip";

    private static final int ROLE_DEFINITION = 0x1;
    private static final double SCIP_CONFIDENCE = 1.0d;

    private final String indexFileName;
    private final long maxIndexBytes;
    private final int maxMessageBytes;

    public ScipCodeIndexImporter() {
        this(
                DEFAULT_INDEX_FILE,
                ScipIndexLimits.maxIndexBytesFromEnvironment(),
                ScipIndexLimits.maxMessageBytesFromEnvironment());
    }

    public ScipCodeIndexImporter(String indexFileName) {
        this(
                indexFileName,
                ScipIndexLimits.maxIndexBytesFromEnvironment(),
                ScipIndexLimits.maxMessageBytesFromEnvironment());
    }

    ScipCodeIndexImporter(String indexFileName, long maxIndexBytes, int maxMessageBytes) {
        this.indexFileName = Objects.requireNonNull(indexFileName, "indexFileName");
        if (indexFileName.isBlank()) {
            throw new IllegalArgumentException("indexFileName ne doit pas être vide");
        }
        if (maxIndexBytes <= 0) {
            throw new IllegalArgumentException("maxIndexBytes doit être strictement positif");
        }
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes doit être strictement positif");
        }
        this.maxIndexBytes = maxIndexBytes;
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public String sourceProvider() {
        return SOURCE_PROVIDER;
    }

    @Override
    public Optional<CodeIntelligenceSnapshot> importIndex(Path projectRoot) throws IOException {
        ProjectPathGuard pathGuard = new ProjectPathGuard(projectRoot);
        Path root = pathGuard.root();
        Path indexCandidate = pathGuard.resolve(Path.of(indexFileName));
        Path indexFile;
        try {
            indexFile = pathGuard.requireRegularFile(indexCandidate);
        } catch (IOException missingOrUnsafeIndex) {
            if (Files.exists(indexCandidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(indexCandidate)) {
                throw missingOrUnsafeIndex;
            }
            return Optional.empty();
        }

        List<IndexedSymbol> symbols = new ArrayList<>();
        List<IndexedRelation> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();

        try (InputStream input = new BufferedInputStream(
                SafeFileIO.newInputStreamNoFollow(indexFile, maxIndexBytes))) {
            while (true) {
                long rawTag = readVarintOrEof(input);
                if (rawTag < 0) {
                    break;
                }
                int tag = checkedInt(rawTag, "tag SCIP");
                int fieldNumber = tag >>> 3;
                int wireType = tag & 0x7;
                if (fieldNumber == 2 && wireType == WireType.LENGTH_DELIMITED) {
                    byte[] documentPayload = readLengthDelimited(input, maxMessageBytes);
                    importDocument(
                            pathGuard,
                            parseDocument(documentPayload, maxMessageBytes),
                            symbols,
                            relations,
                            relationKeys);
                } else {
                    skipField(input, wireType);
                }
            }
        }

        return Optional.of(new CodeIntelligenceSnapshot(SOURCE_PROVIDER, symbols, relations));
    }

    private static void importDocument(
            ProjectPathGuard pathGuard,
            ScipDocument document,
            List<IndexedSymbol> symbols,
            List<IndexedRelation> relations,
            Set<String> relationKeys) throws IOException {
        String relativePath = normalizeRelativePath(pathGuard, document.relativePath());
        if (relativePath == null) {
            return;
        }
        int sourceLineCount = canonicalLineCount(pathGuard, relativePath);

        for (ScipSymbolInformation symbolInformation : document.symbols()) {
            ScipOccurrence definition = findDefinition(document.occurrences(), symbolInformation.symbol());
            SymbolKind symbolKind = mapKind(symbolInformation.kind());
            if (definition != null && definition.range() != null && symbolKind != null) {
                SourceRange validatedRange = validateDefinitionRange(
                        relativePath,
                        definition.range(),
                        sourceLineCount);
                String name = symbolName(symbolInformation);
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
                            relationship.symbol(),
                            relations,
                            relationKeys);
                }
                if (relationship.reference()) {
                    addRelation(
                            relativePath,
                            RelationKind.REFERENCES,
                            symbolInformation.symbol(),
                            relationship.symbol(),
                            relations,
                            relationKeys);
                }
                if (relationship.typeDefinition()) {
                    addRelation(
                            relativePath,
                            RelationKind.TYPE_DEFINITION,
                            symbolInformation.symbol(),
                            relationship.symbol(),
                            relations,
                            relationKeys);
                }
                if (relationship.definition()) {
                    addRelation(
                            relativePath,
                            RelationKind.DEFINITION_OF,
                            symbolInformation.symbol(),
                            relationship.symbol(),
                            relations,
                            relationKeys);
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
                    occurrence.symbol(),
                    relations,
                    relationKeys);
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

    private static int canonicalLineCount(ProjectPathGuard pathGuard, String relativePath) throws IOException {
        Path source = pathGuard.requireRegularFile(pathGuard.resolve(Path.of(relativePath)));
        long lineCount = SafeFileIO.readStringNoFollow(source).lines().count();
        if (lineCount > Integer.MAX_VALUE) {
            throw new IOException("Source file contains too many lines to validate SCIP symbol ranges: "
                    + relativePath);
        }
        return (int) lineCount;
    }

    private static void addRelation(
            String relativePath,
            RelationKind kind,
            String source,
            String target,
            List<IndexedRelation> relations,
            Set<String> relationKeys) {
        if (source.isBlank() || target.isBlank()) {
            return;
        }
        String key = relativePath + '\u0000' + kind + '\u0000' + source + '\u0000' + target;
        if (!relationKeys.add(key)) {
            return;
        }
        relations.add(new IndexedRelation(
                relativePath,
                new SymbolRelation(kind, source, target, SCIP_CONFIDENCE, SOURCE_PROVIDER)));
    }

    private static ScipOccurrence findDefinition(List<ScipOccurrence> occurrences, String symbol) {
        for (ScipOccurrence occurrence : occurrences) {
            if (symbol.equals(occurrence.symbol()) && isDefinition(occurrence.roles())) {
                return occurrence;
            }
        }
        return null;
    }

    private static boolean isDefinition(int roles) {
        return (roles & ROLE_DEFINITION) != 0;
    }

    private static String normalizeRelativePath(ProjectPathGuard pathGuard, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path resolved = pathGuard.resolve(Path.of(relativePath));
        return pathGuard.root().relativize(resolved).toString().replace('\\', '/');
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

    private static ScipDocument parseDocument(byte[] payload, int maxMessageBytes) throws IOException {
        ProtoReader reader = new ProtoReader(payload, maxMessageBytes);
        String relativePath = "";
        List<ScipOccurrence> occurrences = new ArrayList<>();
        List<ScipSymbolInformation> symbols = new ArrayList<>();

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1 -> relativePath = reader.readString(wireType);
                case 2 -> occurrences.add(parseOccurrence(reader.readMessage(wireType)));
                case 3 -> symbols.add(parseSymbolInformation(reader.readMessage(wireType)));
                default -> reader.skipField(wireType);
            }
        }
        return new ScipDocument(relativePath, List.copyOf(occurrences), List.copyOf(symbols));
    }

    private static ScipOccurrence parseOccurrence(ProtoReader reader) throws IOException {
        List<Integer> legacyRange = new ArrayList<>();
        String symbol = "";
        int roles = 0;
        SourceRange typedRange = null;

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1 -> {
                    if (wireType == WireType.VARINT) {
                        legacyRange.add(reader.readInt32(wireType));
                    } else if (wireType == WireType.LENGTH_DELIMITED) {
                        legacyRange.addAll(reader.readPackedInt32(wireType));
                    } else {
                        reader.skipField(wireType);
                    }
                }
                case 2 -> symbol = reader.readString(wireType);
                case 3 -> roles = reader.readInt32(wireType);
                case 8 -> typedRange = parseSingleLineRange(reader.readMessage(wireType));
                case 9 -> typedRange = parseMultiLineRange(reader.readMessage(wireType));
                default -> reader.skipField(wireType);
            }
        }

        SourceRange range = typedRange != null ? typedRange : legacyRange(legacyRange);
        return new ScipOccurrence(symbol, roles, range);
    }

    private static ScipSymbolInformation parseSymbolInformation(ProtoReader reader) throws IOException {
        String symbol = "";
        int kind = 0;
        String displayName = "";
        String signature = "";
        List<ScipRelationship> relationships = new ArrayList<>();

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1 -> symbol = reader.readString(wireType);
                case 4 -> relationships.add(parseRelationship(reader.readMessage(wireType)));
                case 5 -> kind = reader.readInt32(wireType);
                case 6 -> displayName = reader.readString(wireType);
                case 7 -> signature = parseSignature(reader.readMessage(wireType));
                default -> reader.skipField(wireType);
            }
        }
        return new ScipSymbolInformation(
                symbol,
                kind,
                displayName,
                signature,
                List.copyOf(relationships));
    }

    private static ScipRelationship parseRelationship(ProtoReader reader) throws IOException {
        String symbol = "";
        boolean reference = false;
        boolean implementation = false;
        boolean typeDefinition = false;
        boolean definition = false;

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1 -> symbol = reader.readString(wireType);
                case 2 -> reference = reader.readBoolean(wireType);
                case 3 -> implementation = reader.readBoolean(wireType);
                case 4 -> typeDefinition = reader.readBoolean(wireType);
                case 5 -> definition = reader.readBoolean(wireType);
                default -> reader.skipField(wireType);
            }
        }
        return new ScipRelationship(symbol, reference, implementation, typeDefinition, definition);
    }

    private static String parseSignature(ProtoReader reader) throws IOException {
        String text = "";
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNumber == 5) {
                text = reader.readString(wireType);
            } else {
                reader.skipField(wireType);
            }
        }
        return text;
    }

    private static SourceRange parseSingleLineRange(ProtoReader reader) throws IOException {
        int line = -1;
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            if (fieldNumber == 1) {
                line = reader.readInt32(wireType);
            } else {
                reader.skipField(wireType);
            }
        }
        return new SourceRange(line, line);
    }

    private static SourceRange parseMultiLineRange(ProtoReader reader) throws IOException {
        int startLine = -1;
        int endLine = -1;
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1 -> startLine = reader.readInt32(wireType);
                case 3 -> endLine = reader.readInt32(wireType);
                default -> reader.skipField(wireType);
            }
        }
        return new SourceRange(startLine, endLine);
    }

    private static SourceRange legacyRange(List<Integer> values) {
        if (values.size() == 3) {
            return new SourceRange(values.get(0), values.get(0));
        }
        if (values.size() == 4) {
            return new SourceRange(values.get(0), values.get(2));
        }
        return null;
    }

    private static long readVarintOrEof(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return -1L;
        }
        long value = first & 0x7fL;
        if ((first & 0x80) == 0) {
            return value;
        }
        int shift = 7;
        for (int index = 1; index < 10; index++) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("Varint SCIP tronqué");
            }
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("Varint SCIP invalide");
    }

    private static byte[] readLengthDelimited(InputStream input, int maxMessageBytes) throws IOException {
        long rawLength = readRequiredVarint(input);
        if (rawLength > maxMessageBytes) {
            throw new IOException(
                    "Message SCIP trop volumineux : " + rawLength
                            + " octets (maximum " + maxMessageBytes + ")");
        }
        int length = checkedInt(rawLength, "longueur SCIP");
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Message SCIP tronqué");
        }
        return payload;
    }

    private static long readRequiredVarint(InputStream input) throws IOException {
        long value = readVarintOrEof(input);
        if (value < 0) {
            throw new EOFException("Varint SCIP attendu");
        }
        return value;
    }

    private static void skipField(InputStream input, int wireType) throws IOException {
        switch (wireType) {
            case WireType.VARINT -> readRequiredVarint(input);
            case WireType.FIXED_64 -> skipFully(input, 8);
            case WireType.LENGTH_DELIMITED -> skipFully(input, checkedInt(readRequiredVarint(input), "longueur SCIP"));
            case WireType.FIXED_32 -> skipFully(input, 4);
            default -> throw new IOException("Type de fil Protobuf SCIP non supporté : " + wireType);
        }
    }

    private static void skipFully(InputStream input, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= checkedInt(skipped, "octets ignorés");
                continue;
            }
            if (input.read() < 0) {
                throw new EOFException("Message SCIP tronqué");
            }
            remaining--;
        }
    }

    private static int checkedInt(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(label + " hors limites : " + value);
        }
        return (int) value;
    }

    private record ScipDocument(
            String relativePath,
            List<ScipOccurrence> occurrences,
            List<ScipSymbolInformation> symbols) {
    }

    private record ScipOccurrence(String symbol, int roles, SourceRange range) {
    }

    private record ScipSymbolInformation(
            String symbol,
            int kind,
            String displayName,
            String signature,
            List<ScipRelationship> relationships) {
    }

    private record ScipRelationship(
            String symbol,
            boolean reference,
            boolean implementation,
            boolean typeDefinition,
            boolean definition) {
    }

    private record SourceRange(int startLine, int endLine) {
    }

    private static final class WireType {
        private static final int VARINT = 0;
        private static final int FIXED_64 = 1;
        private static final int LENGTH_DELIMITED = 2;
        private static final int FIXED_32 = 5;

        private WireType() {
        }
    }

    private static final class ProtoReader {

        private final byte[] data;
        private final int maxMessageBytes;
        private int position;

        private ProtoReader(byte[] data, int maxMessageBytes) {
            this.data = Objects.requireNonNull(data, "data");
            this.maxMessageBytes = maxMessageBytes;
        }

        private boolean hasRemaining() {
            return position < data.length;
        }

        private int readTag() throws IOException {
            return checkedInt(readVarint(), "tag Protobuf");
        }

        private int readInt32(int wireType) throws IOException {
            requireWireType(wireType, WireType.VARINT);
            return checkedInt(readVarint(), "entier Protobuf");
        }

        private boolean readBoolean(int wireType) throws IOException {
            requireWireType(wireType, WireType.VARINT);
            return readVarint() != 0;
        }

        private String readString(int wireType) throws IOException {
            ProtoReader reader = readMessage(wireType);
            return new String(reader.data, StandardCharsets.UTF_8);
        }

        private ProtoReader readMessage(int wireType) throws IOException {
            requireWireType(wireType, WireType.LENGTH_DELIMITED);
            long rawLength = readVarint();
            if (rawLength > maxMessageBytes) {
                throw new IOException(
                        "Message Protobuf SCIP trop volumineux : " + rawLength
                                + " octets (maximum " + maxMessageBytes + ")");
            }
            int length = checkedInt(rawLength, "longueur Protobuf");
            ensureAvailable(length);
            byte[] payload = new byte[length];
            System.arraycopy(data, position, payload, 0, length);
            position += length;
            return new ProtoReader(payload, maxMessageBytes);
        }

        private List<Integer> readPackedInt32(int wireType) throws IOException {
            ProtoReader packed = readMessage(wireType);
            List<Integer> values = new ArrayList<>();
            while (packed.hasRemaining()) {
                values.add(checkedInt(packed.readVarint(), "entier Protobuf compacté"));
            }
            return values;
        }

        private void skipField(int wireType) throws IOException {
            switch (wireType) {
                case WireType.VARINT -> readVarint();
                case WireType.FIXED_64 -> skip(8);
                case WireType.LENGTH_DELIMITED -> skip(checkedInt(readVarint(), "longueur Protobuf"));
                case WireType.FIXED_32 -> skip(4);
                default -> throw new IOException("Type de fil Protobuf non supporté : " + wireType);
            }
        }

        private long readVarint() throws IOException {
            long value = 0L;
            int shift = 0;
            for (int index = 0; index < 10; index++) {
                ensureAvailable(1);
                int next = data[position++] & 0xff;
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IOException("Varint Protobuf invalide");
        }

        private void skip(int length) throws IOException {
            ensureAvailable(length);
            position += length;
        }

        private void ensureAvailable(int length) throws EOFException {
            if (length < 0 || position < 0 || position > data.length || length > data.length - position) {
                throw new EOFException("Message Protobuf SCIP tronqué");
            }
        }

        private static void requireWireType(int actual, int expected) throws IOException {
            if (actual != expected) {
                throw new IOException("Type de fil Protobuf inattendu : " + actual + ", attendu : " + expected);
            }
        }
    }
}
