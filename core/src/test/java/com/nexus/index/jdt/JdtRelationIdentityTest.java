package com.nexus.index.jdt;

import com.nexus.index.IndexedRelation;
import com.nexus.index.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdtRelationIdentityTest {

    @Test
    void localDeduplicationKeyKeepsDistinctFileProvenance(@TempDir Path temporaryDirectory) throws Exception {
        JdtRelationCollector provider = new JdtRelationCollector(new com.fasterxml.jackson.databind.ObjectMapper(),
                JdtLanguageServerCodeIntelligenceProvider.SnapshotLimits.defaults());
        Map<String, IndexedRelation> relations = new LinkedHashMap<>();
        provider.addRelation( relations, "src/One.java", RelationKind.REFERENCES, "demo.Source", "demo.Target");
        provider.addRelation( relations, "src/Two.java", RelationKind.REFERENCES, "demo.Source", "demo.Target");
        provider.addRelation( relations, "src/One.java", RelationKind.REFERENCES, "demo.Source", "demo.Target");

        assertEquals(2, relations.size());
        Set<String> paths = relations.values().stream()
                .map(IndexedRelation::relativePath)
                .collect(Collectors.toSet());
        assertEquals(Set.of("src/One.java", "src/Two.java"), paths);
    }
}
