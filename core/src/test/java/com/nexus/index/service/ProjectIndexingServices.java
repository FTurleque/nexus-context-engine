package com.nexus.index.service;

import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.IndexRepository;
import com.nexus.index.LanguageAnalyzer;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.minos.MinosCodeIndexImporter;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.project.ProjectRepository;
import com.nexus.search.SearchIndex;
import com.nexus.index.SemanticIndexing;

import java.time.Duration;
import java.util.List;

/**
 * Fabrique de test de {@link ProjectIndexingService}. Le service n'expose qu'un
 * constructeur public, sans valeur par défaut de {@link ProjectIndexLockManager} ;
 * ces variantes de commodité fournissent un verrouillage limité au processus,
 * suffisant pour les tests unitaires et d'intégration locaux.
 */
public final class ProjectIndexingServices {

    private static final Duration DEFAULT_TIMEOUT = ProjectIndexingService.DEFAULT_PROVIDER_TIMEOUT;

    private ProjectIndexingServices() {
    }

    public static ProjectIndexingService of(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex) {
        return withImporters(projectRepository, indexRepository, scanner, analyzers, searchIndex, List.of());
    }

    public static ProjectIndexingService withImporters(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters) {
        return withProviders(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, List.of());
    }

    public static ProjectIndexingService withProviders(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders) {
        return withSemantic(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, SemanticIndexing.disabled());
    }

    public static ProjectIndexingService withSemantic(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexing semanticIndexingService) {
        return withProviderTimeout(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService, DEFAULT_TIMEOUT);
    }

    public static ProjectIndexingService withProviderTimeout(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexing semanticIndexingService,
            Duration providerTimeout) {
        return withLockManager(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService, providerTimeout,
                ProjectIndexLockManager.processLocalOnly());
    }

    public static ProjectIndexingService withLockManager(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexing semanticIndexingService,
            Duration providerTimeout,
            ProjectIndexLockManager projectIndexLockManager) {
        return withBatching(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService, providerTimeout,
                projectIndexLockManager,
                ProjectIndexingService.DEFAULT_INDEX_DOCUMENT_BATCH_FILES,
                ProjectIndexingService.DEFAULT_INDEX_DOCUMENT_BATCH_BYTES);
    }

    public static ProjectIndexingService withBatching(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexing semanticIndexingService,
            Duration providerTimeout,
            ProjectIndexLockManager projectIndexLockManager,
            int indexDocumentBatchFiles,
            long indexDocumentBatchBytes) {
        return new ProjectIndexingService(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService,
                new MinosCodeIndexImporter(), providerTimeout, projectIndexLockManager,
                indexDocumentBatchFiles, indexDocumentBatchBytes);
    }
}
