package com.nexus.persistence.sqlite;

import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.SymbolRelation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SqliteIndexRepository implements IndexRepository {
    private final SqliteDatabase database;
    private final SqliteFileQueries fileQueries;
    private final SqliteSymbolSearch symbolSearch;
    private final SqliteRelationQueries relationQueries;
    private final SqliteGraphQueries graphQueries;
    private final SqliteFileWriter fileWriter;
    private final SqliteProviderWriter providerWriter;

    public SqliteIndexRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.fileQueries = new SqliteFileQueries(database);
        this.symbolSearch = new SqliteSymbolSearch(database);
        this.relationQueries = new SqliteRelationQueries(database);
        this.graphQueries = new SqliteGraphQueries(database);
        this.fileWriter = new SqliteFileWriter(database);
        this.providerWriter = new SqliteProviderWriter(database);
    }

    @Override
    public ReadSession openReadSession() { return database.openReadSession(); }

    @Override
    public Map<String, IndexedFile> findFiles(UUID projectId) {
        return fileQueries.findFiles(projectId);
    }

    @Override
    public Map<String, IndexedFile> findFiles(UUID projectId, Set<String> relativePaths) {
        return fileQueries.findFiles(projectId, relativePaths);
    }

    @Override
    public List<IndexedSymbol> findSymbols(UUID projectId) {
        return fileQueries.findSymbols(projectId);
    }

    @Override
    public Map<String, String> findTypeOwners(UUID projectId) {
        return fileQueries.findTypeOwners(projectId);
    }

    @Override
    public List<IndexedSymbol> searchSymbols(UUID projectId, String query, int limit) {
        return symbolSearch.searchSymbols(projectId, query, limit);
    }

    @Override
    public List<SymbolRelation> findRelations(UUID projectId) {
        return relationQueries.findRelations(projectId);
    }

    @Override
    public List<SymbolRelation> findImportRelations(UUID projectId) {
        return relationQueries.findImportRelations(projectId);
    }

    @Override
    public Map<String, Set<String>> findGraphNeighbors(
            UUID projectId,
            Set<String> relativePaths,
            int maxEdges) {
        return graphQueries.findGraphNeighbors(projectId, relativePaths, maxEdges);
    }

    @Override
    public Set<String> findExternalProviders(UUID projectId) {
        return fileQueries.findExternalProviders(projectId);
    }

    @Override
    public List<SymbolRelation> searchRelations(UUID projectId, String symbol, int limit) {
        return relationQueries.searchRelations(projectId, symbol, limit);
    }

    @Override
    public void applyChanges(UUID projectId, List<IndexedFileUpdate> updates, Set<String> removedPaths) {
        fileWriter.applyChanges(projectId, updates, removedPaths);
    }

    @Override
    public void replaceExternalCodeIntelligence(UUID projectId, CodeIntelligenceSnapshot snapshot) {
        providerWriter.replaceExternalCodeIntelligence(projectId, snapshot);
    }

    @Override
    public IndexStatistics statistics(UUID projectId) {
        return fileQueries.statistics(projectId);
    }

    @Override
    public long generation(UUID projectId) {
        return fileQueries.generation(projectId);
    }
}
