package com.nexus.index;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface IndexRepository {

    Map<String, IndexedFile> findFiles(UUID projectId);

    List<IndexedSymbol> findSymbols(UUID projectId);

    List<SymbolRelation> findRelations(UUID projectId);

    void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths);

    void replaceExternalCodeIntelligence(UUID projectId, CodeIntelligenceSnapshot snapshot);

    IndexStatistics statistics(UUID projectId);
}
