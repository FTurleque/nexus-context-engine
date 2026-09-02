package com.nexus.ranking.graph;

import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectGraphBuilderProjectionTest {

    @Test
    void delegatesOnlyToBoundedNeighborhoodProjection() {
        UUID projectId = UUID.randomUUID();
        AtomicReference<Set<String>> requestedPaths = new AtomicReference<>();
        AtomicReference<Integer> requestedLimit = new AtomicReference<>();
        IndexRepository repository = new IndexRepository() {
            @Override
            public Map<String, IndexedFile> findFiles(UUID ignored) { return Map.of(); }

            @Override
            public List<IndexedSymbol> findSymbols(UUID ignored) {
                throw new AssertionError("Graph must not materialize every symbol");
            }

            @Override
            public List<SymbolRelation> findRelations(UUID ignored) {
                throw new AssertionError("Graph must not materialize every relation");
            }

            @Override
            public Map<String, Set<String>> findGraphNeighbors(
                    UUID ignored,
                    Set<String> relativePaths,
                    int maxEdges) {
                requestedPaths.set(Set.copyOf(relativePaths));
                requestedLimit.set(maxEdges);
                return Map.of("src/App.java", Set.of("src/Dependency.java"));
            }

            @Override
            public void applyChanges(UUID ignored, List<IndexedFileUpdate> updates, Set<String> removedPaths) { }

            @Override
            public void replaceExternalCodeIntelligence(UUID ignored, CodeIntelligenceSnapshot snapshot) { }

            @Override
            public IndexStatistics statistics(UUID ignored) { return new IndexStatistics(0, 0, 0); }
        };

        Map<String, Set<String>> neighbors =
                new ProjectGraphBuilder(repository).neighbors(projectId, Set.of("src/App.java"));

        assertEquals(Set.of("src/App.java"), requestedPaths.get());
        assertEquals(ProjectGraphBuilder.MAX_NEIGHBOR_EDGES, requestedLimit.get());
        assertEquals(Set.of("src/Dependency.java"), neighbors.get("src/App.java"));
    }
}
