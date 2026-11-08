package com.nexus.index.service;

import com.nexus.index.CanonicalIndexFingerprint;
import com.nexus.index.CodeIndexImporter;
import com.nexus.index.CodeIntelligenceProvider;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.ExternalCodeIntelligencePayloadImporter;
import com.nexus.index.FileCategory;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexingReport;
import com.nexus.index.LanguageAnalyzer;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.ScannedFile;
import com.nexus.index.SemanticIndexing;
import com.nexus.index.scan.ProjectScanResult;
import com.nexus.index.scan.ProjectScanner;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectReadiness;
import com.nexus.project.ProjectRepository;
import com.nexus.search.SearchIndex;

import java.io.IOException;
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
    public static final int DEFAULT_INDEX_DOCUMENT_BATCH_FILES = 128;
    public static final long DEFAULT_INDEX_DOCUMENT_BATCH_BYTES = 16L * 1024L * 1024L;

    private static final String CONCURRENT_INDEXING_MESSAGE = "Une indexation est déjà en cours pour le projet ";

    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectScanner scanner;
    private final SearchIndex searchIndex;
    private final List<CodeIntelligenceProvider> codeIntelligenceProviders;
    private final SemanticIndexing semanticIndexingService;
    private final ExternalCodeIntelligenceRefresher externalRefresher;
    private final IndexDocumentPublisher documentPublisher;
    private final ExternalCodeIntelligencePayloadImporter payloadImporter;
    private final ProjectIndexLockManager projectIndexLockManager;
    private final ConcurrentMap<UUID, LockSlot> projectLocks = new ConcurrentHashMap<>();

    /**
     * Seul constructeur public : composition de production. Le gestionnaire de
     * verrou inter-processus est explicite, sans valeur par défaut, et le timeout
     * des providers est résolu par la composition root ({@link #providerTimeout(Map)}).
     * Les variantes de commodité des tests vivent dans une fabrique de test du même
     * paquetage.
     */
    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexing semanticIndexingService,
            ExternalCodeIntelligencePayloadImporter payloadImporter,
            Duration providerTimeout,
            ProjectIndexLockManager projectIndexLockManager) {
        this(projectRepository, indexRepository, scanner, analyzers, searchIndex,
                codeIndexImporters, codeIntelligenceProviders, semanticIndexingService,
                payloadImporter, providerTimeout, projectIndexLockManager,
                DEFAULT_INDEX_DOCUMENT_BATCH_FILES, DEFAULT_INDEX_DOCUMENT_BATCH_BYTES);
    }

    ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex,
            List<CodeIndexImporter> codeIndexImporters,
            List<CodeIntelligenceProvider> codeIntelligenceProviders,
            SemanticIndexing semanticIndexingService,
            ExternalCodeIntelligencePayloadImporter payloadImporter,
            Duration providerTimeout,
            ProjectIndexLockManager projectIndexLockManager,
            int indexDocumentBatchFiles,
            long indexDocumentBatchBytes) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        Objects.requireNonNull(analyzers, "analyzers");
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        Objects.requireNonNull(codeIndexImporters, "codeIndexImporters");
        this.codeIntelligenceProviders = List.copyOf(
                Objects.requireNonNull(codeIntelligenceProviders, "codeIntelligenceProviders"));
        this.semanticIndexingService = Objects.requireNonNull(semanticIndexingService, "semanticIndexingService");
        this.payloadImporter = Objects.requireNonNull(payloadImporter, "payloadImporter");
        this.externalRefresher = new ExternalCodeIntelligenceRefresher(indexRepository, codeIndexImporters,
                codeIntelligenceProviders, providerTimeout);
        this.projectIndexLockManager = Objects.requireNonNull(projectIndexLockManager, "projectIndexLockManager");
        if (indexDocumentBatchFiles <= 0) {
            throw new IllegalArgumentException("indexDocumentBatchFiles must be greater than zero");
        }
        if (indexDocumentBatchBytes <= 0) {
            throw new IllegalArgumentException("indexDocumentBatchBytes must be greater than zero");
        }
        this.documentPublisher = new IndexDocumentPublisher(analyzers, indexRepository, this.searchIndex, semanticIndexingService,
                indexDocumentBatchFiles, indexDocumentBatchBytes);
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

    /**
     * Import d'un export MINOS fourni explicitement : même point d'entrée
     * d'écriture que l'indexation, donc même verrou single-flight JVM puis même
     * verrou inter-processus, et même diagnostic en cas d'indexation concurrente.
     */
    public CodeIntelligenceSnapshot importExternalCodeIntelligence(UUID projectId, String payload)
            throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(payload, "payload");
        LockSlot lockSlot = retainProjectLock(projectId);
        if (!lockSlot.lock.tryLock()) {
            releaseProjectLock(projectId, lockSlot);
            throw new IllegalStateException(CONCURRENT_INDEXING_MESSAGE + projectId);
        }
        try (ProjectIndexLockManager.LockHandle ignored = projectIndexLockManager.acquire(projectId)) {
            ProjectDescriptor project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));
            ProjectReadiness.requireReady(project);
            Set<String> indexedFiles = indexRepository.findFiles(projectId).keySet();
            CodeIntelligenceSnapshot snapshot = payloadImporter.importPayload(project.rootPath(), indexedFiles, payload);
            indexRepository.replaceExternalCodeIntelligence(projectId, snapshot);
            return snapshot;
        } finally {
            lockSlot.lock.unlock();
            releaseProjectLock(projectId, lockSlot);
        }
    }

    /**
     * Lit un payload d'export UTF-8 sous la borne de transport de l'adaptateur
     * (128 MiB pour MINOS, ADR-0044), sans fermer le flux.
     */
    public String readExternalCodeIntelligencePayload(java.io.InputStream input) throws IOException {
        return payloadImporter.readBoundedPayload(input);
    }

    private IndexingReport index(
            UUID projectId,
            boolean explicitRebuild,
            boolean includeCodeIntelligenceProviders) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        LockSlot lockSlot = retainProjectLock(projectId);
        if (!lockSlot.lock.tryLock()) {
            releaseProjectLock(projectId, lockSlot);
            throw new IllegalStateException(CONCURRENT_INDEXING_MESSAGE + projectId);
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
            // READY ne suffit pas : un dérivé lexical peut avoir été supprimé,
            // restauré depuis une ancienne sauvegarde ou construit avec une
            // politique de redaction incompatible. Dans ces cas l'incrémental
            // laisserait des documents absents ou obsolètes.
            if (!fullRebuild && !searchIndex.isPresent(projectId)) {
                fullRebuild = true;
            }

            ProjectScanResult scanResult = scanner.scanWithDiagnostics(project.rootPath());
            List<String> diagnostics = new ArrayList<>(scanResult.diagnostics());
            ScanDiff diff = diffAgainstIndex(projectId, scanResult);
            boolean semanticFullRebuild = semanticIndexingService.enabled()
                    && (fullRebuild || !semanticIndexingService.isCompatible(projectId, diff.canonicalFingerprint()));

            PublicationOutcome publication = publishDocuments(
                    projectId, project, diff, fullRebuild, semanticFullRebuild, diagnostics);

            Map<String, Long> providerDurationsMs = new LinkedHashMap<>();
            refreshExternalProviders(
                    projectId, project, diff, publication, includeCodeIntelligenceProviders,
                    diagnostics, providerDurationsMs);

            verifyRepositoryUnchanged(project, diff.canonicalFingerprint());
            return recordStatus(
                    projectId, project, startedAt, scanResult, diff, publication, fullRebuild,
                    diagnostics, providerDurationsMs);
        } catch (IOException | RuntimeException exception) {
            markFailed(project, exception);
            throw exception;
        }
    }

    /** Étape « diff » : confronte le scan à l'index canonique existant. */
    private ScanDiff diffAgainstIndex(UUID projectId, ProjectScanResult scanResult) {
        List<ScannedFile> scannedFiles = scanResult.files();
        String canonicalFingerprint = CanonicalIndexFingerprint.fromScannedFiles(scannedFiles);
        Map<String, IndexedFile> existingFiles = indexRepository.findFiles(projectId);
        Set<String> scannedPaths = scannedFiles.stream()
                .map(ScannedFile::relativePath)
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> removedPaths = new HashSet<>(existingFiles.keySet());
        removedPaths.removeAll(scannedPaths);
        Set<String> searchRemovedPaths = new HashSet<>(removedPaths);
        scannedFiles.stream()
                .filter(file -> !IndexDocumentPublisher.isGenericSearchEligible(file.category()))
                .map(ScannedFile::relativePath)
                .forEach(searchRemovedPaths::add);
        return new ScanDiff(
                scannedFiles, canonicalFingerprint, existingFiles, scannedPaths, removedPaths, searchRemovedPaths);
    }

    /**
     * Étape « publication » : un writer Lucene par index dérivé et une session d'écriture SQLite
     * pour toute l'indexation (DATA-0045, DATA-0062). Les lots sont accumulés sans être visibles ;
     * fermer la publication sans la publier annule les écritures Lucene et écarte les analyses en
     * attente, de sorte que la génération précédente reste intacte.
     */
    private PublicationOutcome publishDocuments(
            UUID projectId,
            ProjectDescriptor project,
            ScanDiff diff,
            boolean fullRebuild,
            boolean semanticFullRebuild,
            List<String> diagnostics) throws IOException {
        try (IndexPublication publication = documentPublisher.openPublication(
                projectId, diff.canonicalFingerprint(), fullRebuild, semanticFullRebuild)) {
            documentPublisher.prepareDerivedIndexes(
                    publication, fullRebuild, semanticFullRebuild, diff.searchRemovedPaths());

            IndexDocumentPublisher.PublishOutcome outcome = documentPublisher.publish(publication,
                    project.rootPath(), diff.scannedFiles(), diff.existingFiles(), fullRebuild,
                    semanticFullRebuild, diagnostics);

            boolean codeSourcesChanged = codeIntelligenceSourcesChanged(
                    fullRebuild, outcome.codeSourcesUpdated(), diff.removedPaths(), diff.existingFiles());
            Set<String> staleExternalProviders = codeSourcesChanged
                    ? indexRepository.findExternalProviders(projectId)
                    : Set.of();

            // Barrière de publication. Les index dérivés sont committés avant la génération
            // canonique : tout lecteur qui suit la génération voit alors un Lucene déjà
            // committé. Un échec après le commit Lucene laisse le projet FAILED, donc
            // reconstruit au prochain passage ; rien n'est servi avant READY.
            publication.commitDerivedIndexes();
            publication.publishCanonical(diff.removedPaths());
            return new PublicationOutcome(outcome.updatedFiles(), staleExternalProviders);
        }
    }

    /** Étape « providers externes » : purge des providers périmés puis rafraîchissement. */
    private void refreshExternalProviders(
            UUID projectId,
            ProjectDescriptor project,
            ScanDiff diff,
            PublicationOutcome publication,
            boolean includeCodeIntelligenceProviders,
            List<String> diagnostics,
            Map<String, Long> providerDurationsMs) throws IOException {
        if (!publication.staleExternalProviders().isEmpty()) {
            externalRefresher.purgeExternalCodeIntelligence(projectId, publication.staleExternalProviders());
            diagnostics.add("external code intelligence invalidated: "
                    + String.join(", ", publication.staleExternalProviders()));
        }

        externalRefresher.refreshImportedCodeIntelligence(
                projectId, project.rootPath(), diff.scannedPaths(), diagnostics, providerDurationsMs);
        if (includeCodeIntelligenceProviders) {
            externalRefresher.refreshActiveCodeIntelligence(
                    projectId, project.rootPath(), diagnostics, providerDurationsMs);
        }
    }

    /**
     * Le scan final détecte les mutations observables par rapport au fingerprint
     * ayant servi à construire les index. FAILED interdit l'accès aux dérivés
     * partiels et force leur reconstruction. Sans snapshot filesystem, ni ce
     * scan ni save(READY) ne sont atomiques face aux écritures externes : une
     * mutation après le scan reste possible. READY atteste la génération
     * indexée, pas l'immuabilité du repository vivant.
     */
    private void verifyRepositoryUnchanged(ProjectDescriptor project, String canonicalFingerprint)
            throws IOException {
        ProjectScanResult finalScan = scanner.scanWithDiagnostics(project.rootPath());
        String finalFingerprint = CanonicalIndexFingerprint.fromScannedFiles(finalScan.files());
        if (!canonicalFingerprint.equals(finalFingerprint)) {
            throw new IOException("Le repository a changé pendant l'indexation ; un rebuild est requis");
        }
    }

    /** Étape « statut » : publie READY et construit le rapport. */
    private IndexingReport recordStatus(
            UUID projectId,
            ProjectDescriptor project,
            Instant startedAt,
            ProjectScanResult scanResult,
            ScanDiff diff,
            PublicationOutcome publication,
            boolean fullRebuild,
            List<String> diagnostics,
            Map<String, Long> providerDurationsMs) {
        Set<String> languages = diff.scannedFiles().stream()
                .map(ScannedFile::language)
                .collect(Collectors.toUnmodifiableSet());
        Instant completedAt = Instant.now();
        projectRepository.save(withState(project, IndexStatus.READY, completedAt, languages));
        return new IndexingReport(
                projectId,
                diff.scannedFiles().size(),
                publication.updatedFiles(),
                diff.removedPaths().size(),
                fullRebuild,
                indexRepository.statistics(projectId),
                Duration.between(startedAt, completedAt),
                scanResult.skippedFiles(),
                diagnostics,
                providerDurationsMs);
    }

    private static boolean codeIntelligenceSourcesChanged(
            boolean fullRebuild,
            boolean sourceUpdated,
            Set<String> removedPaths,
            Map<String, IndexedFile> existingFiles) {
        if (fullRebuild || sourceUpdated) {
            return true;
        }
        return removedPaths.stream()
                .map(existingFiles::get)
                .filter(Objects::nonNull)
                .map(IndexedFile::category)
                .anyMatch(ProjectIndexingService::isCodeIntelligenceSource);
    }

    static boolean isCodeIntelligenceSource(FileCategory category) {
        return category == FileCategory.SOURCE || category == FileCategory.TEST;
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

    /** Résout {@value #PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE} depuis l'environnement fourni par la composition root. */
    public static Duration providerTimeout(Map<String, String> environment) {
        String configured = Objects.requireNonNull(environment, "environment")
                .get(PROVIDER_TIMEOUT_ENVIRONMENT_VARIABLE);
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

    private record ScanDiff(
            List<ScannedFile> scannedFiles,
            String canonicalFingerprint,
            Map<String, IndexedFile> existingFiles,
            Set<String> scannedPaths,
            Set<String> removedPaths,
            Set<String> searchRemovedPaths) {
    }

    private record PublicationOutcome(int updatedFiles, Set<String> staleExternalProviders) {
    }

    private static final class LockSlot {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
