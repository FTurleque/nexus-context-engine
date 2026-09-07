package com.nexus.index;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIntelligenceMetadataPolicyTest {

    @Test
    void rejectsOversizedSymbolAndRelationFieldsAtTheDomainBoundary() {
        String oversizedName = "n".repeat(CodeIntelligenceMetadataPolicy.MAX_SYMBOL_NAME_UTF8_BYTES + 1);
        IllegalArgumentException symbolFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new CodeSymbol(
                        SymbolKind.CLASS,
                        oversizedName,
                        "demo.Type",
                        "Type()",
                        1,
                        1,
                        "test"));
        assertTrue(symbolFailure.getMessage().contains("symbol.name"));

        String oversizedReference = "r".repeat(
                CodeIntelligenceMetadataPolicy.MAX_RELATION_REFERENCE_UTF8_BYTES + 1);
        IllegalArgumentException relationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new SymbolRelation(
                        RelationKind.REFERENCES,
                        oversizedReference,
                        "demo.Target",
                        1.0d,
                        "test"));
        assertTrue(relationFailure.getMessage().contains("relation.source"));
    }

    @Test
    void countsUtf8BytesWithoutSplittingSupplementaryCharacters() {
        assertEquals(1, CodeIntelligenceMetadataPolicy.utf8Length("a"));
        assertEquals(2, CodeIntelligenceMetadataPolicy.utf8Length("é"));
        assertEquals(4, CodeIntelligenceMetadataPolicy.utf8Length("😀"));
    }

    @Test
    void rejectsOversizedSnapshotCardinalityBeforeCanonicalization() {
        IndexedSymbol symbol = new IndexedSymbol(
                "src/Type.java",
                new CodeSymbol(SymbolKind.CLASS, "Type", "demo.Type", "Type", 1, 1, "test"));
        List<IndexedSymbol> oversized = Collections.nCopies(
                CodeIntelligenceMetadataPolicy.MAX_SNAPSHOT_SYMBOLS + 1,
                symbol);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CodeIntelligenceSnapshot("test", oversized, List.of()));
        assertTrue(failure.getMessage().contains("symbol limit"));
    }

    @Test
    void rejectsDecodedMetadataAmplificationBeforeBuildingDeduplicationMaps() {
        String maxReference = "r".repeat(CodeIntelligenceMetadataPolicy.MAX_RELATION_REFERENCE_UTF8_BYTES);
        IndexedRelation relation = new IndexedRelation(
                "src/Type.java",
                new SymbolRelation(
                        RelationKind.REFERENCES,
                        maxReference,
                        maxReference,
                        1.0d,
                        "test"));
        int copies = Math.toIntExact(
                CodeIntelligenceMetadataPolicy.MAX_SNAPSHOT_METADATA_UTF8_BYTES
                        / (2L * CodeIntelligenceMetadataPolicy.MAX_RELATION_REFERENCE_UTF8_BYTES)
                        + 2L);
        List<IndexedRelation> oversized = Collections.nCopies(copies, relation);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CodeIntelligenceSnapshot("test", List.of(), oversized));
        assertTrue(failure.getMessage().contains("decoded metadata limit"));
    }
}
