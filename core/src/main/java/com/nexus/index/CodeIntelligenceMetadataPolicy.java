package com.nexus.index;

import java.util.List;
import java.util.Objects;

/**
 * Central resource policy for decoded code-intelligence metadata.
 *
 * <p>Transport limits alone do not bound heap amplification after Protobuf/JSON
 * strings and domain objects have been materialized. These limits therefore
 * apply to every provider/importer at the domain boundary, before snapshot
 * canonicalization creates additional maps and immutable copies.</p>
 */
public final class CodeIntelligenceMetadataPolicy {

    public static final int MAX_RELATIVE_PATH_UTF8_BYTES = 8 * 1024;
    public static final int MAX_SYMBOL_NAME_UTF8_BYTES = 4 * 1024;
    public static final int MAX_QUALIFIED_NAME_UTF8_BYTES = 16 * 1024;
    public static final int MAX_SIGNATURE_UTF8_BYTES = 32 * 1024;
    public static final int MAX_RELATION_REFERENCE_UTF8_BYTES = 16 * 1024;
    public static final int MAX_SOURCE_PROVIDER_UTF8_BYTES = 256;

    public static final int MAX_SNAPSHOT_SYMBOLS = 100_000;
    public static final int MAX_SNAPSHOT_RELATIONS = 250_000;
    public static final long MAX_SNAPSHOT_METADATA_UTF8_BYTES = 64L * 1024L * 1024L;

    private CodeIntelligenceMetadataPolicy() {
    }

    static void validateSymbolFields(
            String name,
            String qualifiedName,
            String signature,
            String sourceProvider) {
        requireUtf8Bound("symbol.name", name, MAX_SYMBOL_NAME_UTF8_BYTES);
        requireUtf8Bound("symbol.qualifiedName", qualifiedName, MAX_QUALIFIED_NAME_UTF8_BYTES);
        requireUtf8Bound("symbol.signature", signature, MAX_SIGNATURE_UTF8_BYTES);
        validateSourceProvider(sourceProvider);
    }

    static void validateRelationFields(String source, String target, String sourceProvider) {
        requireUtf8Bound("relation.source", source, MAX_RELATION_REFERENCE_UTF8_BYTES);
        requireUtf8Bound("relation.target", target, MAX_RELATION_REFERENCE_UTF8_BYTES);
        validateSourceProvider(sourceProvider);
    }

    static void validateSourceProvider(String sourceProvider) {
        requireUtf8Bound("sourceProvider", sourceProvider, MAX_SOURCE_PROVIDER_UTF8_BYTES);
    }

    static void validateRelativePath(String relativePath) {
        requireUtf8Bound("relativePath", relativePath, MAX_RELATIVE_PATH_UTF8_BYTES);
    }

    static void validateSnapshotInput(List<IndexedSymbol> symbols, List<IndexedRelation> relations) {
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(relations, "relations");
        if (symbols.size() > MAX_SNAPSHOT_SYMBOLS) {
            throw new IllegalArgumentException(
                    "code-intelligence snapshot exceeds symbol limit of " + MAX_SNAPSHOT_SYMBOLS);
        }
        if (relations.size() > MAX_SNAPSHOT_RELATIONS) {
            throw new IllegalArgumentException(
                    "code-intelligence snapshot exceeds relation limit of " + MAX_SNAPSHOT_RELATIONS);
        }

        long metadataBytes = 0L;
        for (IndexedSymbol indexedSymbol : symbols) {
            metadataBytes = addBounded(metadataBytes, metadataBytes(indexedSymbol));
        }
        for (IndexedRelation indexedRelation : relations) {
            metadataBytes = addBounded(metadataBytes, metadataBytes(indexedRelation));
        }
    }

    static int utf8Length(String value) {
        Objects.requireNonNull(value, "value");
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7F) {
                bytes += 1L;
            } else if (character <= 0x7FF) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4L;
                index++;
            } else {
                // Conservative for isolated UTF-16 surrogates: never undercount.
                bytes += 3L;
            }
            if (bytes > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) bytes;
    }

    private static void requireUtf8Bound(String field, String value, int maximum) {
        Objects.requireNonNull(value, field);
        int bytes = utf8Length(value);
        if (bytes > maximum) {
            throw new IllegalArgumentException(
                    field + " exceeds UTF-8 metadata limit of " + maximum + " bytes (received " + bytes + ")");
        }
    }

    private static long metadataBytes(IndexedSymbol indexedSymbol) {
        Objects.requireNonNull(indexedSymbol, "indexedSymbol");
        CodeSymbol symbol = indexedSymbol.symbol();
        return (long) utf8Length(indexedSymbol.relativePath())
                + utf8Length(symbol.name())
                + utf8Length(symbol.qualifiedName())
                + utf8Length(symbol.signature())
                + utf8Length(symbol.sourceProvider());
    }

    private static long metadataBytes(IndexedRelation indexedRelation) {
        Objects.requireNonNull(indexedRelation, "indexedRelation");
        SymbolRelation relation = indexedRelation.relation();
        return (long) utf8Length(indexedRelation.relativePath())
                + utf8Length(relation.source())
                + utf8Length(relation.target())
                + utf8Length(relation.sourceProvider());
    }

    private static long addBounded(long current, long addition) {
        if (addition > MAX_SNAPSHOT_METADATA_UTF8_BYTES - current) {
            throw new IllegalArgumentException(
                    "code-intelligence snapshot exceeds decoded metadata limit of "
                            + MAX_SNAPSHOT_METADATA_UTF8_BYTES + " UTF-8 bytes");
        }
        return current + addition;
    }
}
