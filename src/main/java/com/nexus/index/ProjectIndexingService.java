package com.nexus.index;

import com.nexus.index.scan.ProjectScanResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public final class ProjectIndexingService {

    public static final String PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE = "NEXUS_CODE_INTELLIGENCE_TIMEOUT_SECONDS";
    public static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(180);

    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectScanner scanner;
    private final List<LanguageAnalyzer> analyzers;
    private final SearchIndex searchIndex;
    private final List<CodeIndexImporter> codeIndexImporters;
    private final List<CodeIntelligenceProvider> codeIntelligenceProviders;
    private final SemanticIndexingService semanticIndexingService;
    private final ExternalTaskRunner externalTaskRunner;
    private final ProjectIndexLockManager projectIndexLockManager;
    private final ConcurrentMap<UUID, LockSlot> projectLocks = new ConcurrentHashMap<>();

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
        this(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, null);
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
        this(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService,
                providerTimeoutFromEnvironment(), ProjectIndexLockManager.processLocalOnly());
    }

    /**
     * Composition de production : conserve le timeout environnemental et ajoute
     * le verrou inter-processus fourni explicitement par la façade applicative.
     */
    public static ProjectIndexingService withInterProcessLocking(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexingService semanticIndexingService,
            ProjectIndexLockManager projectIndexLockManager) {
        return new ProjectIndexingService(
                projectRepository,
                indexRepository,
                scanner,
                analyzers,
                searchIndex,
                codeIndexImporters,
                codeIntelligenceProviders,
                semanticIndexingService,
                providerTimeoutFromEnvironment(),
                projectIndexLockManager);
    }

    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexingService semanticIndexingService,
            Duration providerTimeout) {
        this(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService,
                providerTimeout, ProjectIndexLockManager.processLocalOnly());
    }

    ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexingService semanticIndexingService,
            Duration providerTimeout,
            ProjectIndexLockManager projectIndexLockManager) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.analyzers = List.copyOf(Objects.requireNonNull(analyzers, "analyzers"));
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.codeIndexImporters = List.copyOf(Objects.requireNonNull(codeIndexImporters, "codeIndexImporters"));
        this.codeIntelligenceProviders = List.copyOf(
                Objects.requireNonNull(codeIntelligenceProviders, "codeIntelligenceProviders"));
        this.semanticIndexingService = semanticIndexingService;
        this.externalTaskRunner = new ExternalTaskRunner(Objects.requireNonNull(providerTimeout, "providerTimeout"));
        this.projectIndexLockManager = Objects.requireNonNull(projectIndexLockManager, "projectIndexLockManager");
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
        LockSlot lockSlot = retainProjectLock(projectId);
        if (!lockSlot.lock.tryLock()) {
            releaseProjectLock(projectId, lockSlot);
            throw new IllegalStateException("Une indexation est déjà en cours pour le projet " + projectId);
        }
        try (ProjectIndexLockManager.LockHandle ignored = projectIndexLockManager.acquire(projectId)) {
            return indexLocked(projectId, explicitRebuild, includeCodeIntelligenceProviders);
        } finally {
            lockSlot.lock.unlock();
            releaseProjectLock(projectId, lockSlot);
        }
    }

    private IndexingReport indexLocked(
            UUID projectId,
            boolean explicitRebuild,
            boolean includeCodeIntelligenceProviders) throws IOException {
        ProjectDescriptor project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));
        if (includeCodeIntelligenceProviders && codeIntelligenceProviders.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aucun CodeIntelligenceProvider actif. Pour JDT LS, configurez NEXUS_JDTLS_HOME avant --deep-java.");
        }

        // Tout état persistant non READY, y compris INDEXING après crash, impose
        // une reconstruction complète. La concurrence active est gérée par les
        // verrous single-flight JVM + OS, pas par un état persistant susceptible
        // de rester bloqué après l'arrêt brutal d'un processus.
        boolean fullRebuild = explicitRebuild || project.indexStatus() != IndexStatus.READY;
        Instant startedAt = Instant.now();
        projectRepository.save(withState(project, IndexStatus.INDEXING, project.lastIndexedAt(), project.languages()));
        try {
            ProjectScanResult scanResult = scanner.scanWithDiagnostics(project.rootPath());
            List<ScannedFile> scannedFiles = scanResult.files();
            List<String> diagnostics = new ArrayList<>(scanResult.diagnostics());
            Map<String, Long> providerDurationsMs = new LinkedHashMap<>();
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
            refreshImportedCodeIntelligence(
                    projectId, project.rootPath(), diagnostics, providerDurationsMs);
            if (includeCodeIntelligenceProviders) {
                refreshActiveCodeIntelligence(
                        projectId, project.rootPath(), diagnostics, providerDurationsMs);
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
                    Duration.between(startedAt, completedAt),
                    scanResult.skippedFiles(),
                    diagnostics,
                    providerDurationsMs);
        } catch (IOException | RuntimeException exception) {
            markFailed(project, exception);
            throw exception;
        }
    }

    private void refreshImportedCodeIntelligence(
            UUID projectId,
            java.nio.file.Path projectRoot,
            List<String> diagnostics,
            Map<String, Long> providerDurationsMs) throws IOException {
        for (CodeIndexImporter importer : codeIndexImporters) {
            long startedAt = System.nanoTime();
            CodeIntelligenceSnapshot snapshot = externalTaskRunner.run(
                    "importer " + importer.sourceProvider(),
                    () -> importer.importIndex(projectRoot)
                            .orElseGet(() -> CodeIntelligenceSnapshot.empty(importer.sourceProvider())));
            validateSnapshotProvider(importer.sourceProvider(), snapshot);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
            long durationMs = elapsedMillis(startedAt);
            providerDurationsMs.put("importer:" + importer.sourceProvider(), durationMs);
            diagnostics.add("importer " + importer.sourceProvider() + " : "
                    + durationMs + " ms, " + snapshot.symbols().size()
                    + " symbole(s), " + snapshot.relations().size() + " relation(s)");
        }
    }

    private void refreshActiveCodeIntelligence(
            UUID projectId,
            java.nio.file.Path projectRoot,
            List<String> diagnostics,
            Map<String, Long> providerDurationsMs) throws IOException {
        for (CodeIntelligenceProvider provider : codeIntelligenceProviders) {
            long startedAt = System.nanoTime();
            CodeIntelligenceSnapshot snapshot = externalTaskRunner.run(
                    "provider " + provider.sourceProvider(),
                    () -> provider.analyze(projectRoot));
            validateSnapshotProvider(provider.sourceProvider(), snapshot);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
            long durationMs = elapsedMillis(startedAt);
            providerDurationsMs.put("provider:" + provider.sourceProvider(), durationMs);
            diagnostics.add("provider " + provider.sourceProvider() + " : "
                    + durationMs + " ms, " + snapshot.symbols().size()
                    + " symbole(s), " + snapshot.relations().size() + " relation(s)");
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

    private LockSlot retainProjectLock(UUID projectId) {
        return projectLocks.compute(projectId, (ignored, existing) -> {
            LockSlot slot = existing == null ? new LockSlot() : existing;
            slot.users++;
            return slot;
        });
    }

    private void releaseProjectLock(UUID projectId, LockSlot expected) {
        projectLocks.computeIfPresent(projectId, (ignored, existing) -> {
            if (existing != expected) {
                return existing;
            }
            existing.users--;
            return existing.users == 0 ? null : existing;
        });
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

    private static Duration providerTimeoutFromEnvironment() {
        String configured = System.getenv(PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PROVIDER_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(configured.trim());
            if (seconds <= 0) {
                throw new IllegalArgumentException(
                        PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE + " doit être strictement positif");
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE + " doit être un entier en secondes", exception);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static final class LockSlot {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
