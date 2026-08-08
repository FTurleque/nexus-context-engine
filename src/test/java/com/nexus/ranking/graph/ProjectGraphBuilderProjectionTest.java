package com.nexus.ranking.graph;

import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectGraphBuilderProjectionTest {

    @Test
    void neverLoadsAllSymbolsOrRelationsWhenBuildingGraph() {
        UUID projectId = UUID.randomUUID();
        IndexRepository repository = new IndexRepository() {
            @Override
            public Map<String, IndexedFile> findFiles(UUID ignored) { return Map.of(); }

            @Override
            public List<IndexedSymbol> findSymbols(UUID ignored) {
                throw new AssertionError("Graph must not materialize every symbol");
            }

            @Override
            public Map<String, String> findTypeOwners(UUID ignored) {
                return Map.of("demo.Dependency", "src/Dependency.java");
            }

            @Override
            public List<SymbolRelation> findRelations(UUID ignored) {
                throw new AssertionError("Graph must not materialize every relation");
            }

            @Override
            public List<SymbolRelation> findImportRelations(UUID ignored) {
                return List.of(new SymbolRelation(
                        RelationKind.IMPORTS,
                        "src/App.java",
                        "demo.Dependency",
                        1.0d));
            }

            @Override
            public void applyChanges(UUID ignored, List<IndexedFileUpdate> updates, Set<String> removedPaths) { }

            @Override
            public void replaceExternalCodeIntelligence(UUID ignored, CodeIntelligenceSnapshot snapshot) { }

            @Override
            public IndexStatistics statistics(UUID ignored) { return new IndexStatistics(0, 0, 0); }

            @Override
            public long generation(UUID ignored) { return 7L; }
        };

        ProjectGraph graph = new ProjectGraphBuilder(repository).build(projectId);
        assertEquals(Set.of("src/Dependency.java"), graph.neighbors("src/App.java"));
        assertEquals(Set.of("src/App.java"), graph.neighbors("src/Dependency.java"));
    }
}
