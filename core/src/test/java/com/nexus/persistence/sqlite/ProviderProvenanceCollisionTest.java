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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-regression tests for provenance correctness: independent providers and distinct facts inside
 * one provider must survive persistence without producing artificial generation bumps.
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

        removeProvider(PROVIDER_A);

        assertFalse(hasSymbolFrom(PROVIDER_A), "A supprimé");
        assertTrue(hasSymbolFrom(PROVIDER_B), "X toujours présent via B après suppression de A");
    }

    @Test
    void removingSecondProviderKeepsSymbolStillSuppliedByFirst() {
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
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(
                        PROVIDER_A,
                        List.of(symbol(PROVIDER_A), symbol(PROVIDER_A)),
                        List.of()));
        assertEquals(1, countEquivalentSymbols(), "doublon interne au snapshot dédupliqué");
    }

    @Test
    void sameProviderKeepsStructurallyDistinctSymbolsAndRefreshGenerationIsStable() {
        IndexedSymbol noArg = collidingMethod(PROVIDER_A, "run()", "demo.App.run", 7, 7);
        IndexedSymbol withArgument = collidingMethod(PROVIDER_A, "run(java.lang.String)", "demo.App.run", 7, 8);
        CodeIntelligenceSnapshot snapshot = new CodeIntelligenceSnapshot(
                PROVIDER_A,
                List.of(noArg, withArgument, noArg),
                List.of());

        long generationBefore = repository.generation(project.id());
        repository.replaceExternalCodeIntelligence(project.id(), snapshot);
        long generationAfterChange = repository.generation(project.id());

        assertEquals(generationBefore + 1, generationAfterChange, "un vrai changement incrémente une fois");
        assertEquals(2, countSymbolsNamedFrom("run", PROVIDER_A),
                "deux faits structurellement distincts du même provider doivent survivre");

        repository.replaceExternalCodeIntelligence(project.id(), snapshot);
        assertEquals(generationAfterChange, repository.generation(project.id()),
                "un refresh strictement identique ne doit pas incrémenter la génération");
        assertEquals(2, countSymbolsNamedFrom("run", PROVIDER_A));

        IndexedSymbol changed = collidingMethod(PROVIDER_A, "run(int)", "demo.App.run", 7, 9);
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(PROVIDER_A, List.of(noArg, changed), List.of()));
        assertEquals(generationAfterChange + 1, repository.generation(project.id()),
                "un changement structurel réel incrémente exactement une fois");
        assertEquals(2, countSymbolsNamedFrom("run", PROVIDER_A));
    }

    @Test
    void equivalentSymbolFromSecondProviderSurvivesRemovalOfFirstAfterCollisionRefresh() {
        IndexedSymbol providerASymbol = collidingMethod(PROVIDER_A, "run()", "demo.App.run", 7, 7);
        IndexedSymbol providerBSymbol = collidingMethod(PROVIDER_B, "run()", "demo.App.run", 7, 7);

        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(PROVIDER_A, List.of(providerASymbol), List.of()));
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(PROVIDER_B, List.of(providerBSymbol), List.of()));
        removeProvider(PROVIDER_A);

        assertEquals(0, countSymbolsNamedFrom("run", PROVIDER_A));
        assertEquals(1, countSymbolsNamedFrom("run", PROVIDER_B));
    }

    @Test
    void sameProviderRefreshReplacesOwnRowsWithoutTouchingOther() {
        persistSymbol(PROVIDER_A);
        persistSymbol(PROVIDER_B);

        persistSymbol(PROVIDER_A);

        assertEquals(1, countSymbolsFrom(PROVIDER_A), "une seule ligne A après refresh");
        assertEquals(1, countSymbolsFrom(PROVIDER_B), "B intact après refresh de A");
    }

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
        return new IndexedSymbol(
                PATH,
                new CodeSymbol(SymbolKind.TYPE, "SharedType", "demo.SharedType", "SharedType", 1, 1, provider));
    }

    private static IndexedSymbol collidingMethod(
            String provider,
            String signature,
            String qualifiedName,
            int startLine,
            int endLine) {
        return new IndexedSymbol(
                PATH,
                new CodeSymbol(
                        SymbolKind.METHOD,
                        "run",
                        qualifiedName,
                        signature,
                        startLine,
                        endLine,
                        provider));
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

    private long countSymbolsNamedFrom(String name, String provider) {
        return repository.findSymbols(project.id()).stream()
                .filter(symbol -> name.equals(symbol.symbol().name()))
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
