package com.nexus.index;

import com.nexus.config.NexusPaths;
import com.nexus.index.java.JavaParserLanguageAnalyzer;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.persistence.sqlite.SqliteDatabase;
import com.nexus.persistence.sqlite.SqliteIndexRepository;
import com.nexus.persistence.sqlite.SqliteProjectRepository;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.search.lucene.LuceneSearchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCodeIntelligenceInvalidationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void purgesSnapshotsFromProvidersThatAreNoLongerConfiguredAfterSourceChanges() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class App { void run() {} }\n");

        NexusPaths paths = new NexusPaths(temporaryDirectory.resolve("nexus-home"));
        SqliteDatabase database = new SqliteDatabase(paths);
        ProjectRepository projectRepository = new SqliteProjectRepository(database);
        IndexRepository indexRepository = new SqliteIndexRepository(database);
        ProjectDescriptor project = new ProjectRegistry(projectRepository).register(projectRoot, "demo");

        ProjectIndexingService service = new ProjectIndexingService(
                projectRepository,
                indexRepository,
                new ProjectScanner(),
                List.of(new JavaParserLanguageAnalyzer()),
                new LuceneSearchIndex(paths));
        service.index(project.id());

        persistExternalSnapshot(indexRepository, project, "jdtls-stale");
        persistExternalSnapshot(indexRepository, project, "minos-stale");

        assertTrue(hasProvider(indexRepository, project, "jdtls-stale"));
        assertTrue(hasProvider(indexRepository, project, "minos-stale"));

        // Aucun provider n'est configuré dans ce service : les snapshots
        // représentent donc un runtime précédent (JDT) ou un import explicite
        // précédent (MINOS).
        Files.writeString(source, "package demo; class App { void run() {} void stop() {} }\n");
        service.index(project.id());

        assertTrue(!hasProvider(indexRepository, project, "jdtls-stale"));
        assertTrue(!hasProvider(indexRepository, project, "minos-stale"));
    }

    private static void persistExternalSnapshot(
            IndexRepository repository,
            ProjectDescriptor project,
            String provider) {
        String path = "src/main/java/demo/App.java";
        repository.replaceExternalCodeIntelligence(
                project.id(),
                new CodeIntelligenceSnapshot(
                        provider,
                        List.of(new IndexedSymbol(
                                path,
                                new CodeSymbol(
                                        SymbolKind.TYPE,
                                        "ExternalType",
                                        provider + ":demo.ExternalType",
                                        "ExternalType",
                                        1,
                                        1,
                                        provider))),
                        List.of(new IndexedRelation(
                                path,
                                new SymbolRelation(
                                        RelationKind.REFERENCES,
                                        "demo.App#run()",
                                        provider + ":demo.ExternalType",
                                        1.0d,
                                        provider)))));
    }

    private static boolean hasProvider(
            IndexRepository repository,
            ProjectDescriptor project,
            String provider) {
        return repository.findSymbols(project.id()).stream()
                        .anyMatch(symbol -> provider.equals(symbol.symbol().sourceProvider()))
                || repository.findRelations(project.id()).stream()
                        .anyMatch(relation -> provider.equals(relation.sourceProvider()));
    }
}
