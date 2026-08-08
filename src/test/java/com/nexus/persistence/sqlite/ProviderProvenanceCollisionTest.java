package com.nexus.persistence.sqlite;

import com.nexus.config.NexusPaths;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexedRelation;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.RelationKind;
import com.nexus.index.SymbolKind;
import com.nexus.index.ProjectIndexingService;
import com.nexus.index.SymbolRelation;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression tests for P1 provenance correctness: two independent providers may describe an
 * equivalent symbol/relation without either provider's fact being lost. Removing one provider must
 * never destroy a fact still supplied by another provider.
 */
class ProviderProvenanceCollisionTest {

    private static final String PATH = "src/main/java/demo/App.java";
    private static final String PROVIDER_A = "minos";
    private static final String PROVIDER_B = "scip";

    @TempDir
    Path temporaryDirectory;

    private IndexRepository repository;
    private ProjectDescriptor project;

    @BeforeEach
    void setUp() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve(PATH);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class App { void run() {} }\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        repository = new SqliteIndexRepository(database);
        project = new ProjectRegistry(projectRepository).register(projectRoot, "demo");

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                repository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths));
        service.index(project.id());
    }

    @Test
    void secondProviderIsNotSwallowedByEquivalentSymbolFromFirstProvider() {
        persistSymbol(PROVIDER_A);
        persistSymbol(PROVIDER_B);

        assertTrue(hasSymbolFrom(PROVIDER_A), "symbole du provider A présent");
        assertTrue(hasSymbolFrom(PROVIDER_B), "symbole équivalent du provider B présent (non avalé)");
        assertEquals(2, countEquivalentSymbols(), "une ligne par provider pour un symbole équivalent");
    }

    @Test
    void removingFirstProviderKeepsSymbolStillSuppliedBySecond() {
        persistSymbol(PROVIDER_A);
        persistSymbol(PROVIDER_B);

        // A est invalidé/supprimé (snapshot vide).
        removeProvider(PROVIDER_A);

        assertFalse(hasSymbolFrom(PROVIDER_A), "A supprimé");
        assertTrue(hasSymbolFrom(PROVIDER_B), "X toujours présent via B après suppression de A");
    }

    @Test
    void removingSecondProviderKeepsSymbolStillSuppliedByFirst() {
        // Ordre inverse : B inséré en premier, puis A.
        persistSymbol(PROVIDER_B);
        persistSymbol(PROVIDER_A);

        removeProvider(PROVIDER_B);

        assertFalse(hasSymbolFrom(PROVIDER_B), "B supprimé");
        assertTrue(hasSymbolFrom(PROVIDER_A), "X toujours présent via A après suppression de B");
    }

    @Test
    void secondProviderIsNotSwallowedByEquivalentRelationFromFirstProvider() {
        persistRelation(PROVIDER_A);
        persistRelation(PROVIDER_B);

        assertTrue(hasRelationFrom(PROVIDER_A), "relation du provider A présente");
        assertTrue(hasRelationFrom(PROVIDER_B), "relation équivalente du provider B présente");
        assertEquals(2, countEquivalentRelations(), "une ligne par provider pour une relation équivalente");
    }

    @Test
    void removingProviderKeepsRelationStillSuppliedByOther() {
        persistRelation(PROVIDER_A);
        persistRelation(PROVIDER_B);

        removeProvider(PROVIDER_A);
        assertFalse(hasRelationFrom(PROVIDER_A));
        assertTrue(hasRelationFrom(PROVIDER_B), "relation toujours présente via B");

        removeProvider(PROVIDER_B);
        assertFalse(hasRelationFrom(PROVIDER_B), "relation via B supprimée quand B disparaît aussi");
    }

    @Test
    void duplicateWithinSameProviderSnapshotIsDeduplicated() {
        // Deux symboles identiques dans le même snapshot d'un provider → une seule ligne.
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(
                        PROVIDER_A,
                        List.of(symbol(PROVIDER_A), symbol(PROVIDER_A)),
                        List.of()));
        assertEquals(1, countEquivalentSymbols(), "doublon interne au snapshot dédupliqué");
    }

    @Test
    void sameProviderRefreshReplacesOwnRowsWithoutTouchingOther() {
        persistSymbol(PROVIDER_A);
        persistSymbol(PROVIDER_B);

        // Refresh de A : ré-insertion du même fait. Ne doit pas dupliquer A ni toucher B.
        persistSymbol(PROVIDER_A);

        assertEquals(1, countSymbolsFrom(PROVIDER_A), "une seule ligne A après refresh");
        assertEquals(1, countSymbolsFrom(PROVIDER_B), "B intact après refresh de A");
    }

    // --- helpers -----------------------------------------------------------------------------

    private void persistSymbol(String provider) {
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(provider, List.of(symbol(provider)), List.of()));
    }

    private void persistRelation(String provider) {
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(provider, List.of(), List.of(relation(provider))));
    }

    private void removeProvider(String provider) {
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(provider, List.of(), List.of()));
    }

    private static IndexedSymbol symbol(String provider) {
        // Symbole EQUIVALENT quelle que soit la provenance : mêmes kind/name/start_line.
        return new IndexedSymbol(
                PATH,
                new CodeSymbol(SymbolKind.TYPE, "SharedType", "demo.SharedType", "SharedType", 1, 1, provider));
    }

    private static IndexedRelation relation(String provider) {
        return new IndexedRelation(
                PATH,
                new SymbolRelation(RelationKind.REFERENCES, "demo.App#run()", "demo.SharedType", 1.0d, provider));
    }

    private boolean hasSymbolFrom(String provider) {
        return countSymbolsFrom(provider) > 0;
    }

    private long countSymbolsFrom(String provider) {
        return repository.findSymbols(project.id()).stream()
                .filter(symbol -> "SharedType".equals(symbol.symbol().name()))
                .filter(symbol -> provider.equals(symbol.symbol().sourceProvider()))
                .count();
    }

    private long countEquivalentSymbols() {
        return repository.findSymbols(project.id()).stream()
                .filter(symbol -> "SharedType".equals(symbol.symbol().name()))
                .count();
    }

    private boolean hasRelationFrom(String provider) {
        return repository.findRelations(project.id()).stream()
                .anyMatch(relation -> provider.equals(relation.sourceProvider())
                        && "demo.SharedType".equals(relation.target()));
    }

    private long countEquivalentRelations() {
        return repository.findRelations(project.id()).stream()
                .filter(relation -> "demo.SharedType".equals(relation.target()))
                .count();
    }
}
