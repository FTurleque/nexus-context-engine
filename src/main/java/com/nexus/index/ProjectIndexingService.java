package com.nexus.index;

import com.nexus.index.scan.ProjectScanner;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRepository;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;
import com.nexus.search.semantic.SemanticIndexingService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProjectIndexingService {

    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectScanner scanner;
    private final List<LanguageAnalyzer> analyzers;
    private final SearchIndex searchIndex;
    private final List<CodeIndexImporter> codeIndexImporters;
    private final List<CodeIntelligenceProvider> codeIntelligenceProviders;
    private final SemanticIndexingService semanticIndexingService;

    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex) {
        this(projectRepository, indexRepository, scanner, analyzers, searchIndex, List.of(), List.of(), null);
    }

    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters) {
        this(projectRepository, indexRepository, scanner, analyzers, searchIndex, codeIndexImporters, List.of(), null);
    }

    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders) {
        this(
                projectRepository,
                indexRepository,
                scanner,
                analyzers,
                searchIndex,
                codeIndexImporters,
                codeIntelligenceProviders,
                null);
    }

    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexingService semanticIndexingService) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.analyzers = List.copyOf(Objects.requireNonNull(analyzers, "analyzers"));
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.codeIndexImporters = List.copyOf(Objects.requireNonNull(codeIndexImporters, "codeIndexImporters"));
        this.codeIntelligenceProviders = List.copyOf(
                Objects.requireNonNull(codeIntelligenceProviders, "codeIntelligenceProviders"));
        this.semanticIndexingService = semanticIndexingService;
    }

    public IndexingReport index(UUID projectId) throws IOException {
        return index(projectId, false, false);
    }

    public IndexingReport rebuild(UUID projectId) throws IOException {
        return index(projectId, true, false);
    }

    public IndexingReport indexWithCodeIntelligence(UUID projectId) throws IOException {
        return index(projectId, false, true);
    }

    public IndexingReport rebuildWithCodeIntelligence(UUID projectId) throws IOException {
        return index(projectId, true, true);
    }

    private IndexingReport index(
            UUID projectId,
            boolean explicitRebuild,
            boolean includeCodeIntelligenceProviders) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        ProjectDescriptor project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));
        if (includeCodeIntelligenceProviders && codeIntelligenceProviders.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aucun CodeIntelligenceProvider actif. Pour JDT LS, configurez NEXUS_JDTLS_HOME avant --deep-java.");
        }

        boolean fullRebuild = explicitRebuild || project.indexStatus() != IndexStatus.READY;
        Instant startedAt = Instant.now();
        projectRepository.save(withState(project, IndexStatus.INDEXING, project.lastIndexedAt(), project.languages()));
        try {
            List<ScannedFile> scannedFiles = scanner.scan(project.rootPath());
            Map<String, IndexedFile> existingFiles = indexRepository.findFiles(projectId);
            Set<String> scannedPaths = scannedFiles.stream()
                    .map(ScannedFile::relativePath)
                    .collect(Collectors.toCollection(HashSet::new));

            Set<String> removedPaths = new HashSet<>(existingFiles.keySet());
            removedPaths.removeAll(scannedPaths);
            Set<String> searchRemovedPaths = new HashSet<>(removedPaths);

            List<IndexedFileUpdate> updates = new ArrayList<>();
            List<SearchDocument> searchDocuments = new ArrayList<>();

            for (ScannedFile scannedFile : scannedFiles) {
                boolean genericSearchEligible = isGenericSearchEligible(scannedFile.category());
                if (!genericSearchEligible) {
                    // Nettoie aussi les index dérivés créés par une ancienne version de
                    // NEXUS, même lorsque le fichier canonique n'a pas changé.
                    searchRemovedPaths.add(scannedFile.relativePath());
                }

                IndexedFile existing = existingFiles.get(scannedFile.relativePath());
                if (!fullRebuild && existing != null && existing.contentHash().equals(scannedFile.contentHash())) {
                    continue;
                }

                AnalysisResult analysis = analyzeScannedFile(project.rootPath(), scannedFile);
                updates.add(new IndexedFileUpdate(scannedFile, analysis));
                if (genericSearchEligible) {
                    searchDocuments.add(new SearchDocument(
                            scannedFile.relativePath(),
                            scannedFile.language(),
                            scannedFile.category(),
                            Files.readString(scannedFile.absolutePath(), StandardCharsets.UTF_8),
                            analysis.symbols()));
                }
            }

            boolean javaSourcesChanged = javaSourcesChanged(fullRebuild, updates, removedPaths, existingFiles);
            indexRepository.applyChanges(projectId, updates, removedPaths);
            refreshImportedCodeIntelligence(projectId, project.rootPath());
            if (includeCodeIntelligenceProviders) {
                refreshActiveCodeIntelligence(projectId, project.rootPath());
            } else if (javaSourcesChanged) {
                purgeActiveCodeIntelligence(projectId);
            }

            if (fullRebuild) {
                searchIndex.rebuild(projectId, searchDocuments);
                if (semanticIndexingService != null) {
                    semanticIndexingService.rebuild(projectId, searchDocuments);
                }
            } else {
                searchIndex.applyChanges(projectId, searchDocuments, searchRemovedPaths);
                if (semanticIndexingService != null) {
                    semanticIndexingService.applyChanges(projectId, searchDocuments, searchRemovedPaths);
                }
            }

            Set<String> languages = scannedFiles.stream()
                    .map(ScannedFile::language)
                    .collect(Collectors.toUnmodifiableSet());
            Instant completedAt = Instant.now();
            projectRepository.save(withState(project, IndexStatus.READY, completedAt, languages));
            return new IndexingReport(
                    projectId,
                    scannedFiles.size(),
                    updates.size(),
                    removedPaths.size(),
                    fullRebuild,
                    indexRepository.statistics(projectId),
                    Duration.between(startedAt, completedAt));
        } catch (IOException | RuntimeException exception) {
            markFailed(project, exception);
            throw exception;
        }
    }

    private void refreshImportedCodeIntelligence(UUID projectId, java.nio.file.Path projectRoot) throws IOException {
        for (CodeIndexImporter importer : codeIndexImporters) {
            CodeIntelligenceSnapshot snapshot = importer.importIndex(projectRoot)
                    .orElseGet(() -> CodeIntelligenceSnapshot.empty(importer.sourceProvider()));
            validateSnapshotProvider(importer.sourceProvider(), snapshot);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
        }
    }

    private void refreshActiveCodeIntelligence(UUID projectId, java.nio.file.Path projectRoot) throws IOException {
        for (CodeIntelligenceProvider provider : codeIntelligenceProviders) {
            CodeIntelligenceSnapshot snapshot = provider.analyze(projectRoot);
            validateSnapshotProvider(provider.sourceProvider(), snapshot);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
        }
    }

    private void purgeActiveCodeIntelligence(UUID projectId) {
        for (CodeIntelligenceProvider provider : codeIntelligenceProviders) {
            indexRepository.replaceExternalCodeIntelligence(
                    projectId,
                    CodeIntelligenceSnapshot.empty(provider.sourceProvider()));
        }
    }

    private static void validateSnapshotProvider(String expectedProvider, CodeIntelligenceSnapshot snapshot)
            throws IOException {
        if (!expectedProvider.equals(snapshot.sourceProvider())) {
            throw new IOException("Le snapshot ne correspond pas au provider " + expectedProvider);
        }
    }

    private static boolean javaSourcesChanged(
            boolean fullRebuild,
            List<IndexedFileUpdate> updates,
            Set<String> removedPaths,
            Map<String, IndexedFile> existingFiles) {
        if (fullRebuild) {
            return true;
        }
        boolean javaUpdated = updates.stream()
                .map(IndexedFileUpdate::file)
                .anyMatch(file -> "java".equalsIgnoreCase(file.language()));
        if (javaUpdated) {
            return true;
        }
        return removedPaths.stream()
                .map(existingFiles::get)
                .filter(Objects::nonNull)
                .anyMatch(file -> "java".equalsIgnoreCase(file.language()));
    }

    private AnalysisResult analyzeScannedFile(java.nio.file.Path projectRoot, ScannedFile file) throws IOException {
        for (LanguageAnalyzer analyzer : analyzers) {
            if (analyzer.supports(file.absolutePath())) {
                return analyzer.analyze(projectRoot, file.absolutePath());
            }
        }
        return new AnalysisResult(file.absolutePath(), file.language(), List.of(), List.of());
    }

    private static boolean isGenericSearchEligible(FileCategory category) {
        return category != FileCategory.INSTRUCTION
                && category != FileCategory.AGENT_PROFILE
                && category != FileCategory.SKILL;
    }

    private void markFailed(ProjectDescriptor project, Exception originalFailure) {
        try {
            projectRepository.save(withState(
                    project,
                    IndexStatus.FAILED,
                    project.lastIndexedAt(),
                    project.languages()));
        } catch (RuntimeException persistenceFailure) {
            originalFailure.addSuppressed(persistenceFailure);
        }
    }

    private static ProjectDescriptor withState(
            ProjectDescriptor project,
            IndexStatus status,
            Instant lastIndexedAt,
            Set<String> languages) {
        return new ProjectDescriptor(
                project.id(),
                project.name(),
                project.rootPath(),
                project.sourceType(),
                languages,
                project.technologies(),
                lastIndexedAt,
                status);
    }
}
