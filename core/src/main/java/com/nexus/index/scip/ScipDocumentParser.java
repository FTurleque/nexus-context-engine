package com.nexus.index.scip;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.nexus.index.scip.ScipPayload.ScipDocument;
import com.nexus.index.scip.ScipPayload.ScipOccurrence;
import com.nexus.index.scip.ScipPayload.ScipRelationship;
import com.nexus.index.scip.ScipPayload.ScipSymbolInformation;
import com.nexus.index.scip.ScipPayload.SourceRange;
import static com.nexus.index.scip.ScipWireInput.*;

/** Composant interne de lecture SCIP bornée. */
final class ScipDocumentParser {
    private static final int MAX_LEGACY_RANGE_VALUES = 4;
    static ScipDocument parseDocument(
            byte[] payload,
            int maxMessageBytes,
            ScipParseBudget parseBudget) throws IOException {
        ScipProtoReader reader = new ScipProtoReader(payload, maxMessageBytes);
        String relativePath = "";
        List<ScipOccurrence> occurrences = new ArrayList<>();
        List<ScipSymbolInformation> symbols = new ArrayList<>();

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case 1 -> relativePath = reader.readString(wireType);
                case 2 -> {
                    // Charge before allocating/copying the nested message.
                    parseBudget.occurrence();
                    occurrences.add(parseOccurrence(reader.readMessage(wireType)));
                }
                case 3 -> {
                    parseBudget.symbolInfo();
                    symbols.add(parseSymbolInformation(reader.readMessage(wireType), parseBudget));
                }
                default -> reader.skipField(wireType);
            }
        }
        return new ScipDocument(relativePath, List.copyOf(occurrences), List.copyOf(symbols));
    }

    private static ScipOccurrence parseOccurrence(ScipProtoReader reader) throws IOException {
        List<Integer> legacyRange = new ArrayList<>(MAX_LEGACY_RANGE_VALUES);
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
                        if (legacyRange.size() >= MAX_LEGACY_RANGE_VALUES) {
                            throw new IOException("Plage legacy SCIP contient plus de "
                                    + MAX_LEGACY_RANGE_VALUES + " entiers");
                        }
                        legacyRange.add(reader.readInt32(wireType));
                    } else if (wireType == WireType.LENGTH_DELIMITED) {
                        legacyRange.addAll(reader.readPackedInt32(
                                wireType,
                                MAX_LEGACY_RANGE_VALUES - legacyRange.size()));
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

    private static ScipSymbolInformation parseSymbolInformation(
            ScipProtoReader reader,
            ScipParseBudget parseBudget) throws IOException {
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
                case 4 -> {
                    // Charge before allocating/copying the nested relationship message.
                    parseBudget.relationship();
                    relationships.add(parseRelationship(reader.readMessage(wireType)));
                }
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

    private static ScipRelationship parseRelationship(ScipProtoReader reader) throws IOException {
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

    private static String parseSignature(ScipProtoReader reader) throws IOException {
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

    private static SourceRange parseSingleLineRange(ScipProtoReader reader) throws IOException {
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

    private static SourceRange parseMultiLineRange(ScipProtoReader reader) throws IOException {
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

}
