package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexedRelation;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalRelationIdentityPersistenceTest {

    private static final String PROVIDER_A = "minos";
    private static final String PROVIDER_B = "scip";
    private static final String FILE_ONE = "src/One.java";
    private static final String FILE_TWO = "src/Two.java";
    private static final String SOURCE = "demo.Source";
    private static final String TARGET = "demo.Target";

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private SqliteIndexRepository repository;
    private UUID projectId;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabase(new NexusPaths(temporaryDirectory.resolve("home")));
        repository = new SqliteIndexRepository(database);
        projectId = UUID.randomUUID();
        try (Connection connection = database.openConnection()) {
            insertProject(connection, projectId);
            insertFile(connection, projectId, FILE_ONE);
            insertFile(connection, projectId, FILE_TWO);
        }
    }

    @Test
    void preservesFileProvenanceAndUsesMaximumConfidenceDeterministically() throws Exception {
        CodeIntelligenceSnapshot snapshot = new CodeIntelligenceSnapshot(
                PROVIDER_A,
                List.of(),
                List.of(
                        relation(FILE_ONE, PROVIDER_A, 0.40d),
                        relation(FILE_TWO, PROVIDER_A, 0.80d),
                        relation(FILE_ONE, PROVIDER_A, 0.90d),
                        relation(FILE_ONE, PROVIDER_A, 0.40d)));

        assertEquals(2, snapshot.relations().size(), "une relation canonique par fichier");
        assertEquals(0.90d, confidenceFor(snapshot.relations(), FILE_ONE));
        assertEquals(0.80d, confidenceFor(snapshot.relations(), FILE_TWO));

        long generationBefore = repository.generation(projectId);
        repository.replaceExternalCodeIntelligence(projectId, snapshot);
        long generationAfterChange = repository.generation(projectId);

        assertEquals(generationBefore + 1, generationAfterChange);
        assertEquals(
                List.of(
                        new PersistedRelation(FILE_ONE, PROVIDER_A, 0.90d),
                        new PersistedRelation(FILE_TWO, PROVIDER_A, 0.80d)),
                persistedRelations());

        repository.replaceExternalCodeIntelligence(projectId, snapshot);
        assertEquals(generationAfterChange, repository.generation(projectId),
                "un refresh strictement identique doit rester un no-op");

        SqliteIndexRepository restartedRepository = new SqliteIndexRepository(database);
        assertEquals(generationAfterChange, restartedRepository.generation(projectId));
        assertEquals(
                List.of(
                        new PersistedRelation(FILE_ONE, PROVIDER_A, 0.90d),
                        new PersistedRelation(FILE_TWO, PROVIDER_A, 0.80d)),
                persistedRelations());
    }

    @Test
    void removesOnlyMissingFileProvenanceAndKeepsOtherProviderIndependent() throws Exception {
        repository.replaceExternalCodeIntelligence(
                projectId,
                new CodeIntelligenceSnapshot(
                        PROVIDER_A,
                        List.of(),
                        List.of(
                                relation(FILE_ONE, PROVIDER_A, 1.0d),
                                relation(FILE_TWO, PROVIDER_A, 1.0d))));
        repository.replaceExternalCodeIntelligence(
                projectId,
                new CodeIntelligenceSnapshot(
                        PROVIDER_B,
                        List.of(),
                        List.of(relation(FILE_ONE, PROVIDER_B, 1.0d))));

        repository.replaceExternalCodeIntelligence(
                projectId,
                new CodeIntelligenceSnapshot(
                        PROVIDER_A,
                        List.of(),
                        List.of(relation(FILE_TWO, PROVIDER_A, 1.0d))));

        assertEquals(
                List.of(
                        new PersistedRelation(FILE_TWO, PROVIDER_A, 1.0d),
                        new PersistedRelation(FILE_ONE, PROVIDER_B, 1.0d)),
                persistedRelations());

        repository.replaceExternalCodeIntelligence(
                projectId,
                CodeIntelligenceSnapshot.empty(PROVIDER_A));

        assertEquals(
                List.of(new PersistedRelation(FILE_ONE, PROVIDER_B, 1.0d)),
                persistedRelations(),
                "supprimer A ne doit jamais supprimer la provenance de B");
    }

    private static IndexedRelation relation(String relativePath, String provider, double confidence) {
        return new IndexedRelation(
                relativePath,
                new SymbolRelation(RelationKind.REFERENCES, SOURCE, TARGET, confidence, provider));
    }

    private static double confidenceFor(List<IndexedRelation> relations, String relativePath) {
        return relations.stream()
                .filter(relation -> relativePath.equals(relation.relativePath()))
                .findFirst()
                .orElseThrow()
                .relation()
                .confidence();
    }

    private List<PersistedRelation> persistedRelations() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT f.relative_path, r.source_provider, r.confidence
                     FROM symbol_relations r
                     JOIN indexed_files f ON f.id = r.file_id
                     WHERE r.project_id = ?
                       AND r.kind = 'REFERENCES'
                       AND r.source_ref = ?
                       AND r.target_ref = ?
                     ORDER BY r.source_provider, f.relative_path
                     """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, SOURCE);
            statement.setString(3, TARGET);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PersistedRelation> relations = new ArrayList<>();
                while (resultSet.next()) {
                    relations.add(new PersistedRelation(
                            resultSet.getString("relative_path"),
                            resultSet.getString("source_provider"),
                            resultSet.getDouble("confidence")));
                }
                return List.copyOf(relations);
            }
        }
    }

    private static void insertProject(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projects(id, name, root_path, source_type, last_indexed_at, index_status)
                VALUES (?, 'relation-identity-test', ?, 'LOCAL', NULL, 'READY')
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, "/relation-identity-test/" + projectId);
            statement.executeUpdate();
        }
    }

    private static void insertFile(Connection connection, UUID projectId, String relativePath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO indexed_files(
                    project_id, relative_path, language, size_bytes,
                    content_hash, modified_at, estimated_tokens, category)
                VALUES (?, ?, 'java', 1, ?, '2026-08-09T00:00:00Z', 1, 'SOURCE')
                """)) {
            statement.setString(1, projectId.toString());
            statement.setString(2, relativePath);
            statement.setString(3, "hash-" + relativePath);
            statement.executeUpdate();
        }
    }

    private record PersistedRelation(String relativePath, String sourceProvider, double confidence) {
    }
}
