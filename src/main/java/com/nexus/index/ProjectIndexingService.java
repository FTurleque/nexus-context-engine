package com.nexus.index;

import com.nexus.index.scan.ProjectScanner;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRepository;
import com.nexus.search.SearchDocument;
import com.nexus.search.SearchIndex;

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

    public ProjectIndexingService(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectScanner scanner,
            List<LanguageAnalyzer> analyzers,
            SearchIndex searchIndex) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.analyzers = List.copyOf(Objects.requireNonNull(analyzers, "analyzers"));
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
    }

    public IndexingReport index(UUID projectId) throws IOException {
        return index(projectId, false);
    }

    public IndexingReport rebuild(UUID projectId) throws IOException {
        return index(projectId, true);
    }

    private IndexingReport index(UUID projectId, boolean explicitRebuild) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        ProjectDescriptor project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));

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
                    // Nettoie aussi les index Lucene créés par une ancienne version de
                    // NEXUS, même lorsque le fichier canonique n'a pas changé.
                    searchRemovedPaths.add(scannedFile.relativePath());
                }

                IndexedFile existing = existingFiles.get(scannedFile.relativePath());
                if (!fullRebuild && existing != null && existing.contentHash().equals(scannedFile.contentHash())) {
                    continue;
                }

                LanguageAnalyzer analyzer = findAnalyzer(scannedFile);
                AnalysisResult analysis = analyzer.analyze(project.rootPath(), scannedFile.absolutePath());
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

            indexRepository.applyChanges(projectId, updates, removedPaths);
            if (fullRebuild) {
                searchIndex.rebuild(projectId, searchDocuments);
            } else {
                searchIndex.applyChanges(projectId, searchDocuments, searchRemovedPaths);
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

    private LanguageAnalyzer findAnalyzer(ScannedFile file) throws IOException {
        return analyzers.stream()
                .filter(analyzer -> analyzer.supports(file.absolutePath()))
                .findFirst()
                .orElseThrow(() -> new IOException("Aucun analyseur disponible pour " + file.relativePath()));
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
