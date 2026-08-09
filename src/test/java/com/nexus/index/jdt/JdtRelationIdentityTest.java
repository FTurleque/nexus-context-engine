package com.nexus.index.jdt;

import com.nexus.index.IndexedRelation;
import com.nexus.index.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
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
        JdtLanguageServerCodeIntelligenceProvider provider =
                new JdtLanguageServerCodeIntelligenceProvider(
                        new JdtLanguageServerCodeIntelligenceProvider.Configuration(
                                temporaryDirectory.resolve("jdtls"),
                                temporaryDirectory.resolve("workspaces"),
                                "java",
                                Duration.ofSeconds(1),
                                10));
        Map<String, IndexedRelation> relations = new LinkedHashMap<>();
        Method addRelation = JdtLanguageServerCodeIntelligenceProvider.class.getDeclaredMethod(
                "addRelation",
                Map.class,
                String.class,
                RelationKind.class,
                String.class,
                String.class);
        addRelation.setAccessible(true);

        addRelation.invoke(provider, relations, "src/One.java", RelationKind.REFERENCES, "demo.Source", "demo.Target");
        addRelation.invoke(provider, relations, "src/Two.java", RelationKind.REFERENCES, "demo.Source", "demo.Target");
        addRelation.invoke(provider, relations, "src/One.java", RelationKind.REFERENCES, "demo.Source", "demo.Target");

        assertEquals(2, relations.size());
        Set<String> paths = relations.values().stream()
                .map(IndexedRelation::relativePath)
                .collect(Collectors.toSet());
        assertEquals(Set.of("src/One.java", "src/Two.java"), paths);
    }
}
