package com.nexus.index.service;

import com.nexus.index.AnalysisResult;
import com.nexus.index.FileCategory;
import com.nexus.index.FileHasher;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexedFile;
import com.nexus.index.IndexedFileUpdate;
import com.nexus.index.LanguageAnalyzer;
import com.nexus.index.ScannedFile;
import com.nexus.index.SearchDocument;
import com.nexus.index.SemanticIndexing;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchIndexSession;
import com.nexus.security.OversizedSecretException;
import com.nexus.security.SafeFileIO;
import com.nexus.security.SensitiveContentRedactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Étape interne du pipeline d’indexation. */
final class IndexDocumentPublisher {
    private final List<LanguageAnalyzer> analyzers;
    private final IndexRepository indexRepository;
    private final SearchIndex searchIndex;
    private final SemanticIndexing semanticIndexingService;
    private final int indexDocumentBatchFiles;
    private final long indexDocumentBatchBytes;
    IndexDocumentPublisher(List<LanguageAnalyzer> analyzers, IndexRepository indexRepository,
            SearchIndex searchIndex, SemanticIndexing semanticIndexingService, int maxFiles, long maxBytes) {
        this.analyzers = List.copyOf(analyzers);
        this.indexRepository = indexRepository;
        this.searchIndex = searchIndex;
        this.semanticIndexingService = Objects.requireNonNull(semanticIndexingService, "semanticIndexingService");
        this.indexDocumentBatchFiles = maxFiles;
        this.indexDocumentBatchBytes = maxBytes;
    }

    /**
     * Résultat de {@link #publish} : les analyses ne sont plus retenues en mémoire, seul ce résumé
     * borné l'est.
     *
     * @param updatedFiles nombre de fichiers nouveaux ou modifiés dont l'analyse a été mise en attente
     * @param codeSourcesUpdated au moins un de ces fichiers est une source ou un test
     */
    record PublishOutcome(int updatedFiles, boolean codeSourcesUpdated) { }

    /**
     * Ouvre les écritures de l'indexation : un writer Lucene par index dérivé, vidé si le rebuild
     * correspondant est demandé, et la session canonique SQLite. En cas d'échec, tout ce qui a déjà
     * été ouvert est annulé.
     */
    IndexPublication openPublication(
            UUID projectId,
            String canonicalFingerprint,
            boolean fullRebuild,
            boolean semanticFullRebuild) throws IOException {
        SearchIndexSession lexical = searchIndex.openSession(projectId, fullRebuild);
        SemanticIndexing.Session semantic = null;
        try {
            if (semanticIndexingService.enabled()) {
                semantic = semanticIndexingService.openSession(projectId, canonicalFingerprint, semanticFullRebuild);
            }
            return new IndexPublication(lexical, semantic, indexRepository.openWriteSession(projectId));
        } catch (IOException | RuntimeException failure) {
            closeQuietly(lexical, failure);
            if (semantic != null) {
                closeQuietly(semantic, failure);
            }
            throw failure;
        }
    }

    private static void closeQuietly(AutoCloseable resource, Exception failure) {
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Publie les documents dérivés des fichiers modifiés. Un fichier dont une valeur sensible dépasse
     * la borne de redaction reste dans l'index structurel, mais n'entre ni dans l'index lexical ni dans
     * l'index sémantique ; son exclusion est ajoutée à {@code diagnostics} sans valeur ni contenu.
     *
     * <p>Les documents et les analyses sont écrits au rythme des lots (128 fichiers ou 16 MiB) :
     * documents dans les writers Lucene de {@code publication}, analyses dans la mise en attente
     * SQLite. Ni les uns ni les autres ne sont visibles avant la barrière de publication.</p>
     */
    PublishOutcome publish(IndexPublication publication, java.nio.file.Path projectRoot,
            List<ScannedFile> scannedFiles, Map<String, IndexedFile> existingFiles,
            boolean fullRebuild, boolean semanticFullRebuild,
            List<String> diagnostics) throws IOException {
        PendingUpdates pendingUpdates = new PendingUpdates(indexDocumentBatchFiles, indexDocumentBatchBytes);
        int updatedFiles = 0;
        boolean codeSourcesUpdated = false;
        Set<String> excludedPaths = new java.util.TreeSet<>();
        IndexDocumentBatch documentBatch = new IndexDocumentBatch(
                indexDocumentBatchFiles,
                indexDocumentBatchBytes);

        for (ScannedFile scannedFile : scannedFiles) {
            DocumentRequirements requirements = requirementsFor(
                    scannedFile, existingFiles.get(scannedFile.relativePath()), fullRebuild, semanticFullRebuild);
            if (!requirements.changed() && !requirements.semantic()) {
                continue;
            }

            if (requirements.anyDocument() && documentBatch.shouldFlushBefore(scannedFile.sizeBytes())) {
                flushBatches(publication, documentBatch, pendingUpdates);
            }

            String snapshotContent = readVerifiedSnapshot(scannedFile);
            AnalysisResult analysis = analyzeScannedFile(projectRoot, scannedFile, snapshotContent);

            if (requirements.changed()) {
                updatedFiles++;
                codeSourcesUpdated |= ProjectIndexingService.isCodeIntelligenceSource(scannedFile.category());
                pendingUpdates.add(new IndexedFileUpdate(scannedFile, analysis));
                if (pendingUpdates.reachedLimit()) {
                    pendingUpdates.stageTo(publication);
                }
            }

            if (requirements.anyDocument()) {
                addSearchDocument(new DocumentSink(publication, documentBatch, pendingUpdates),
                        scannedFile, analysis, snapshotContent, requirements, excludedPaths, diagnostics);
            }
        }
        flushBatches(publication, documentBatch, pendingUpdates);
        removeStaleDocuments(publication, excludedPaths, fullRebuild, semanticFullRebuild);

        return new PublishOutcome(updatedFiles, codeSourcesUpdated);
    }

    /** Documents dérivés qu'un fichier scanné doit produire pour cette publication. */
    private record DocumentRequirements(boolean changed, boolean lexical, boolean semantic) {

        boolean anyDocument() {
            return lexical || semantic;
        }
    }

    /** Destination des documents d'un lot : writers de la publication et mises à jour SQLite en attente. */
    private record DocumentSink(
            IndexPublication publication, IndexDocumentBatch documentBatch, PendingUpdates pendingUpdates) {
    }

    private DocumentRequirements requirementsFor(ScannedFile scannedFile, IndexedFile existing,
            boolean fullRebuild, boolean semanticFullRebuild) {
        boolean genericSearchEligible = isGenericSearchEligible(scannedFile.category());
        boolean changed = fullRebuild
                || existing == null
                || !existing.contentHash().equals(scannedFile.contentHash());
        boolean lexicalDocumentRequired = changed && genericSearchEligible;
        boolean semanticDocumentRequired = semanticIndexingService.enabled()
                && genericSearchEligible
                && (semanticFullRebuild || changed);
        return new DocumentRequirements(changed, lexicalDocumentRequired, semanticDocumentRequired);
    }

    /** Relit le fichier sans suivre de lien et refuse un contenu différent de celui du scan. */
    private static String readVerifiedSnapshot(ScannedFile scannedFile) throws IOException {
        byte[] snapshotBytes = SafeFileIO.readBytesNoFollow(scannedFile.absolutePath());
        String snapshotHash = FileHasher.sha256(snapshotBytes);
        if (!snapshotHash.equals(scannedFile.contentHash())) {
            throw new IOException(
                    "Le fichier a changé pendant l'indexation : " + scannedFile.relativePath());
        }
        return new String(snapshotBytes, StandardCharsets.UTF_8);
    }

    /**
     * Ajoute le document redigé au lot courant, ou exclut le fichier des index lexical et sémantique
     * quand la redaction le refuse.
     */
    private void addSearchDocument(DocumentSink sink, ScannedFile scannedFile,
            AnalysisResult analysis, String snapshotContent, DocumentRequirements requirements,
            Set<String> excludedPaths, List<String> diagnostics) throws IOException {
        String redactedContent = redactedOrNull(snapshotContent);
        if (redactedContent == null) {
            excludedPaths.add(scannedFile.relativePath());
            diagnostics.add("INDEX_REDACTION_LIMIT: fichier exclu des index lexical et sémantique : "
                    + scannedFile.relativePath() + " (" + OversizedSecretException.PUBLIC_REASON + ")");
            return;
        }
        SearchDocument document = new SearchDocument(
                scannedFile.relativePath(),
                scannedFile.language(),
                scannedFile.category(),
                redactedContent,
                analysis.symbols());
        sink.documentBatch().add(
                document,
                requirements.lexical(),
                requirements.semantic(),
                scannedFile.sizeBytes());
        if (sink.documentBatch().reachedLimit()) {
            flushBatches(sink.publication(), sink.documentBatch(), sink.pendingUpdates());
        }
    }

    /** Contenu redigé publié aux index dérivés, ou {@code null} si la redaction refuse le fichier. */
    private static String redactedOrNull(String content) {
        try {
            return SensitiveContentRedactor.redact(content);
        } catch (OversizedSecretException exception) {
            return null;
        }
    }

    /** Un document indexé avant que son fichier ne devienne non redigeable ne doit pas rester visible. */
    private void removeStaleDocuments(IndexPublication publication, Set<String> excludedPaths,
            boolean fullRebuild, boolean semanticFullRebuild) throws IOException {
        if (excludedPaths.isEmpty()) {
            return;
        }
        if (!fullRebuild) {
            publication.applyLexical(List.of(), excludedPaths);
        }
        if (semanticIndexingService.enabled() && !semanticFullRebuild) {
            publication.applySemantic(List.of(), excludedPaths);
        }
    }

    /**
     * Applique aux index dérivés les suppressions de chemins, avant la publication des lots. Les
     * vidages demandés par un rebuild ont déjà eu lieu à l'ouverture des sessions.
     */
    void prepareDerivedIndexes(
            IndexPublication publication,
            boolean fullRebuild,
            boolean semanticFullRebuild,
            Set<String> searchRemovedPaths) throws IOException {
        if (!fullRebuild && !searchRemovedPaths.isEmpty()) {
            publication.applyLexical(List.of(), searchRemovedPaths);
        }
        if (semanticIndexingService.enabled() && !semanticFullRebuild && !searchRemovedPaths.isEmpty()) {
            publication.applySemantic(List.of(), searchRemovedPaths);
        }
    }

    private void flushBatches(
            IndexPublication publication,
            IndexDocumentBatch batch,
            PendingUpdates pendingUpdates) throws IOException {
        if (!batch.isEmpty()) {
            if (!batch.lexicalDocuments.isEmpty()) {
                publication.applyLexical(batch.lexicalDocuments, Set.of());
            }
            if (semanticIndexingService.enabled() && !batch.semanticDocuments.isEmpty()) {
                publication.applySemantic(batch.semanticDocuments, Set.of());
            }
            batch.clear();
        }
        pendingUpdates.stageTo(publication);
    }

    private AnalysisResult analyzeScannedFile(
            java.nio.file.Path projectRoot,
            ScannedFile file,
            String content) throws IOException {
        for (LanguageAnalyzer analyzer : analyzers) {
            if (analyzer.supports(file.absolutePath())) {
                return analyzer.analyze(projectRoot, file.absolutePath(), content);
            }
        }
        return new AnalysisResult(file.absolutePath(), file.language(), List.of(), List.of());
    }

    static boolean isGenericSearchEligible(FileCategory category) {
        return category != FileCategory.INSTRUCTION
                && category != FileCategory.AGENT_PROFILE
                && category != FileCategory.SKILL;
    }

    /**
     * Analyses des fichiers modifiés pas encore mises en attente en SQLite : bornées par le même lot
     * que les documents (128 fichiers ou 16 MiB de sources), jamais par le nombre de fichiers modifiés.
     */
    private static final class PendingUpdates {
        private final int maxFiles;
        private final long maxBytes;
        private final List<IndexedFileUpdate> updates = new ArrayList<>();
        private long retainedBytes;

        private PendingUpdates(int maxFiles, long maxBytes) {
            this.maxFiles = maxFiles;
            this.maxBytes = maxBytes;
        }

        private void add(IndexedFileUpdate update) {
            updates.add(update);
            retainedBytes += update.file().sizeBytes();
        }

        private boolean reachedLimit() {
            return updates.size() >= maxFiles || retainedBytes >= maxBytes;
        }

        private void stageTo(IndexPublication publication) {
            if (updates.isEmpty()) {
                return;
            }
            publication.stage(List.copyOf(updates));
            updates.clear();
            retainedBytes = 0L;
        }
    }

    private static final class IndexDocumentBatch {
        private final int maxFiles;
        private final long maxBytes;
        private final List<SearchDocument> lexicalDocuments = new ArrayList<>();
        private final List<SearchDocument> semanticDocuments = new ArrayList<>();
        private int retainedDocuments;
        private long retainedBytes;

        private IndexDocumentBatch(int maxFiles, long maxBytes) {
            this.maxFiles = maxFiles;
            this.maxBytes = maxBytes;
        }

        private boolean shouldFlushBefore(long nextBytes) {
            if (isEmpty()) {
                return false;
            }
            return retainedDocuments >= maxFiles
                    || nextBytes > maxBytes - retainedBytes;
        }

        private void add(
                SearchDocument document,
                boolean lexical,
                boolean semantic,
                long sourceBytes) {
            if (lexical) {
                lexicalDocuments.add(document);
            }
            if (semantic) {
                semanticDocuments.add(document);
            }
            retainedDocuments++;
            retainedBytes += sourceBytes;
        }

        private boolean reachedLimit() {
            return retainedDocuments >= maxFiles || retainedBytes >= maxBytes;
        }

        private boolean isEmpty() {
            return retainedDocuments == 0;
        }

        private void clear() {
            lexicalDocuments.clear();
            semanticDocuments.clear();
            retainedDocuments = 0;
            retainedBytes = 0L;
        }
    }

}
