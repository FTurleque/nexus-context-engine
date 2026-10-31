package com.nexus.index;

import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;
import com.nexus.search.semantic.SemanticIndexingService;
import com.nexus.security.OversizedSecretException;
import com.nexus.security.SafeFileIO;
import com.nexus.security.SensitiveContentRedactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Étape interne du pipeline d’indexation. */
final class IndexDocumentPublisher {
    private final List<LanguageAnalyzer> analyzers;
    private final SearchIndex searchIndex;
    private final SemanticIndexingService semanticIndexingService;
    private final int indexDocumentBatchFiles;
    private final long indexDocumentBatchBytes;
    IndexDocumentPublisher(List<LanguageAnalyzer> analyzers, SearchIndex searchIndex,
            SemanticIndexingService semanticIndexingService, int maxFiles, long maxBytes) {
        this.analyzers = List.copyOf(analyzers);
        this.searchIndex = searchIndex;
        this.semanticIndexingService = semanticIndexingService;
        this.indexDocumentBatchFiles = maxFiles;
        this.indexDocumentBatchBytes = maxBytes;
    }
    /**
     * Publie les documents dérivés des fichiers modifiés. Un fichier dont une valeur sensible dépasse
     * la borne de redaction reste dans l'index structurel, mais n'entre ni dans l'index lexical ni dans
     * l'index sémantique ; son exclusion est ajoutée à {@code diagnostics} sans valeur ni contenu.
     */
    List<IndexedFileUpdate> publish(UUID projectId, java.nio.file.Path projectRoot,
            List<ScannedFile> scannedFiles, Map<String, IndexedFile> existingFiles,
            String canonicalFingerprint, boolean fullRebuild, boolean semanticFullRebuild,
            List<String> diagnostics) throws IOException {
        List<IndexedFileUpdate> updates = new ArrayList<>();
        Set<String> excludedPaths = new java.util.TreeSet<>();
        IndexDocumentBatch documentBatch = new IndexDocumentBatch(
                indexDocumentBatchFiles,
                indexDocumentBatchBytes);

        for (ScannedFile scannedFile : scannedFiles) {
            boolean genericSearchEligible = isGenericSearchEligible(scannedFile.category());
            IndexedFile existing = existingFiles.get(scannedFile.relativePath());
            boolean changed = fullRebuild
                    || existing == null
                    || !existing.contentHash().equals(scannedFile.contentHash());
            boolean lexicalDocumentRequired = changed && genericSearchEligible;
            boolean semanticDocumentRequired = semanticIndexingService != null
                    && genericSearchEligible
                    && (semanticFullRebuild || changed);
            if (!changed && !semanticDocumentRequired) {
                continue;
            }

            if ((lexicalDocumentRequired || semanticDocumentRequired)
                    && documentBatch.shouldFlushBefore(scannedFile.sizeBytes())) {
                flushDocumentBatch(projectId, canonicalFingerprint, documentBatch);
            }

            byte[] snapshotBytes = SafeFileIO.readBytesNoFollow(scannedFile.absolutePath());
            String snapshotHash = FileHasher.sha256(snapshotBytes);
            if (!snapshotHash.equals(scannedFile.contentHash())) {
                throw new IOException(
                        "Le fichier a changé pendant l'indexation : " + scannedFile.relativePath());
            }
            String snapshotContent = new String(snapshotBytes, StandardCharsets.UTF_8);
            AnalysisResult analysis = analyzeScannedFile(projectRoot, scannedFile, snapshotContent);

            if (changed) {
                updates.add(new IndexedFileUpdate(scannedFile, analysis));
            }

            String redactedContent = lexicalDocumentRequired || semanticDocumentRequired
                    ? redactedOrNull(snapshotContent)
                    : null;
            if ((lexicalDocumentRequired || semanticDocumentRequired) && redactedContent == null) {
                excludedPaths.add(scannedFile.relativePath());
                diagnostics.add("INDEX_REDACTION_LIMIT: fichier exclu des index lexical et sémantique : "
                        + scannedFile.relativePath() + " (" + OversizedSecretException.PUBLIC_REASON + ")");
            } else if (lexicalDocumentRequired || semanticDocumentRequired) {
                SearchDocument document = new SearchDocument(
                        scannedFile.relativePath(),
                        scannedFile.language(),
                        scannedFile.category(),
                        redactedContent,
                        analysis.symbols());
                documentBatch.add(
                        document,
                        lexicalDocumentRequired,
                        semanticDocumentRequired,
                        scannedFile.sizeBytes());
                if (documentBatch.reachedLimit()) {
                    flushDocumentBatch(projectId, canonicalFingerprint, documentBatch);
                }
            }
        }
        flushDocumentBatch(projectId, canonicalFingerprint, documentBatch);
        removeStaleDocuments(projectId, canonicalFingerprint, excludedPaths, fullRebuild, semanticFullRebuild);

        return List.copyOf(updates);
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
    private void removeStaleDocuments(UUID projectId, String canonicalFingerprint, Set<String> excludedPaths,
            boolean fullRebuild, boolean semanticFullRebuild) throws IOException {
        if (excludedPaths.isEmpty()) {
            return;
        }
        if (!fullRebuild) {
            searchIndex.applyChanges(projectId, List.of(), excludedPaths);
        }
        if (semanticIndexingService != null && !semanticFullRebuild) {
            semanticIndexingService.applyChanges(projectId, canonicalFingerprint, List.of(), excludedPaths);
        }
    }
    void prepareDerivedIndexes(
            UUID projectId,
            String canonicalFingerprint,
            boolean fullRebuild,
            boolean semanticFullRebuild,
            Set<String> searchRemovedPaths) throws IOException {
        if (fullRebuild) {
            searchIndex.rebuild(projectId, List.of());
        } else if (!searchRemovedPaths.isEmpty()) {
            searchIndex.applyChanges(projectId, List.of(), searchRemovedPaths);
        }

        if (semanticIndexingService == null) {
            return;
        }
        if (semanticFullRebuild) {
            semanticIndexingService.rebuild(projectId, canonicalFingerprint, List.of());
        } else if (!searchRemovedPaths.isEmpty()) {
            semanticIndexingService.applyChanges(
                    projectId,
                    canonicalFingerprint,
                    List.of(),
                    searchRemovedPaths);
        }
    }

    private void flushDocumentBatch(
            UUID projectId,
            String canonicalFingerprint,
            IndexDocumentBatch batch) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        if (!batch.lexicalDocuments.isEmpty()) {
            searchIndex.applyChanges(projectId, batch.lexicalDocuments, Set.of());
        }
        if (semanticIndexingService != null && !batch.semanticDocuments.isEmpty()) {
            semanticIndexingService.applyChanges(
                    projectId,
                    canonicalFingerprint,
                    batch.semanticDocuments,
                    Set.of());
        }
        batch.clear();
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
