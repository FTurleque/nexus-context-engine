package com.nexus.application;

import com.nexus.config.NexusPaths;
import com.nexus.context.ContextBuilder;
import com.nexus.context.ContextBundle;
import com.nexus.context.ContextRequest;
import com.nexus.context.FederatedContextBundle;
import com.nexus.context.FederatedContextService;
import com.nexus.index.CodeIntelligenceSnapshot;
import com.nexus.index.IndexRepository;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexedSymbol;
import com.nexus.index.IndexingReport;
import com.nexus.index.service.ProjectIndexingService;
import com.nexus.index.ProjectIndexLockManager;
import com.nexus.index.SymbolRelation;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRegistry;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.candidate.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.FederatedSearchOutcome;
import com.nexus.search.FederatedSearchService;
import com.nexus.search.QueryPolicy;
import com.nexus.config.ResultLimitPolicy;
import com.nexus.search.SearchIndex;
import com.nexus.search.SearchService;
import com.nexus.search.semantic.SemanticSearchConfiguration;
import com.nexus.search.semantic.SemanticSearchIndex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Façade applicative indépendante des adaptateurs clients.
 *
 * <p>La CLI, REST et MCP doivent déléguer à cette façade afin de partager les
 * mêmes gates de cohérence, la même composition des providers et les mêmes
 * politiques de ranking/context.</p>
 */
public final class NexusApplication implements AutoCloseable {

    private final ProjectRepository projectRepository;
    private final IndexRepository indexRepository;
    private final ProjectRegistry projectRegistry;
    private final ProjectIndexingService indexingService;
    private final ProjectIndexLockManager projectIndexLockManager;
    private final SearchService searchService;
    private final FederatedSearchService federatedSearchService;
    private final ContextBuilder contextBuilder;
    private final FederatedContextService federatedContextService;
    private final SearchIndex searchIndex;
    private final SemanticSearchIndex semanticSearchIndex;
    private final boolean semanticSearchEnabled;

    NexusApplication(
            ProjectRepository projectRepository,
            IndexRepository indexRepository,
            ProjectRegistry projectRegistry,
            ProjectIndexingService indexingService,
            ProjectIndexLockManager projectIndexLockManager,
            SearchService searchService,
            FederatedSearchService federatedSearchService,
            ContextBuilder contextBuilder,
            FederatedContextService federatedContextService,
            SearchIndex searchIndex,
            SemanticSearchIndex semanticSearchIndex,
            boolean semanticSearchEnabled) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.indexRepository = Objects.requireNonNull(indexRepository, "indexRepository");
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.indexingService = Objects.requireNonNull(indexingService, "indexingService");
        this.projectIndexLockManager = Objects.requireNonNull(projectIndexLockManager, "projectIndexLockManager");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.federatedSearchService = Objects.requireNonNull(federatedSearchService, "federatedSearchService");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.federatedContextService = Objects.requireNonNull(federatedContextService, "federatedContextService");
        this.searchIndex = Objects.requireNonNull(searchIndex, "searchIndex");
        this.semanticSearchIndex = semanticSearchIndex;
        this.semanticSearchEnabled = semanticSearchEnabled;
    }

    /** Compose NEXUS avec les opt-ins opérationnels explicitement présents dans l'environnement. */
    public static NexusApplication create(NexusPaths paths) throws IOException {
        return NexusApplicationFactory.create(paths, NexusApplicationFactory.semanticSearchConfigurationFromEnvironment(), false);
    }

    /** Compose NEXUS avec une configuration sémantique fournie explicitement par l'appelant. */
    public static NexusApplication create(
            NexusPaths paths,
            SemanticSearchConfiguration semanticSearchConfiguration) throws IOException {
        return NexusApplicationFactory.create(paths, semanticSearchConfiguration, false);
    }

    /**
     * Compose NEXUS pour un processus longue durée (REST/MCP) en conservant des
     * readers/searchers Lucene et un cache de contexte Git bornés entre les requêtes.
     * Les writers Lucene restent operation-scoped afin de préserver les verrous
     * inter-processus existants.
     */
    public static NexusApplication createLongLived(NexusPaths paths) throws IOException {
        return NexusApplicationFactory.create(paths, NexusApplicationFactory.semanticSearchConfigurationFromEnvironment(), true);
    }

    /** Variante longue durée avec configuration sémantique explicite. */
    public static NexusApplication createLongLived(
            NexusPaths paths,
            SemanticSearchConfiguration semanticSearchConfiguration) throws IOException {
        return NexusApplicationFactory.create(paths, semanticSearchConfiguration, true);
    }

    public List<ProjectDescriptor> listProjects() {
        return projectRegistry.list();
    }

    public ProjectDescriptor registerProject(Path rootPath, String name) throws IOException {
        return projectRegistry.register(rootPath, name);
    }

    public ProjectDescriptor getProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));
    }

    public ProjectDescriptor resolveProject(String selector) {
        Objects.requireNonNull(selector, "selector");
        String normalized = selector.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Le sélecteur de projet ne peut pas être vide");
        }

        UUID projectId = null;
        try {
            projectId = UUID.fromString(normalized);
        } catch (IllegalArgumentException invalidUuid) {
            // Le sélecteur n'est pas un UUID : on tente alors seulement la résolution par nom.
        }
        if (projectId != null) {
            return getProject(projectId);
        }

        List<ProjectDescriptor> matches = projectRepository.findAll().stream()
                .filter(project -> project.name().equalsIgnoreCase(normalized))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Plusieurs projets portent le nom '" + normalized + "'. Utilisez leur UUID.");
        }
        throw new IllegalArgumentException("Projet introuvable : " + normalized);
    }

    public IndexOperation index(UUID projectId, boolean rebuild, boolean deepJava) throws IOException {
        ProjectDescriptor project = getProject(projectId);
        IndexingReport report;
        if (deepJava) {
            report = rebuild
                    ? indexingService.rebuildWithCodeIntelligence(projectId)
                    : indexingService.indexWithCodeIntelligence(projectId);
        } else {
            report = rebuild
                    ? indexingService.rebuild(projectId)
                    : indexingService.index(projectId);
        }
        ProjectDescriptor updatedProject = projectRepository.findById(projectId).orElse(project);
        return new IndexOperation(updatedProject, report);
    }

    /** Le payload est fourni explicitement ; NEXUS ne lance jamais MINOS. */
    public CodeIntelligenceSnapshot importMinos(UUID projectId, String payload) throws IOException {
        return indexingService.importExternalCodeIntelligence(projectId, payload);
    }

    /**
     * Lit un payload MINOS UTF-8 sur un flux fourni par l'appelant (stdin pour la
     * CLI), sous la borne de transport de l'import (ARCH-0080), sans fermer le flux.
     *
     * @throws IOException si le flux dépasse la borne de transport ou ne peut être lu
     */
    public String readMinosPayload(InputStream input) throws IOException {
        return indexingService.readExternalCodeIntelligencePayload(input);
    }

    public IndexStatistics inspect(UUID projectId) {
        return readProject(projectId, (reads, project) -> indexRepository.statistics(projectId));
    }

    public SearchOperation search(UUID projectId, String query, int limit, boolean explain) throws IOException {
        String resolvedQuery = requireQuery(query);
        int resolvedLimit = positiveLimit(limit);
        long startedAt = System.nanoTime();
        return readProject(projectId, (reads, project) -> {
            List<RankedCandidate> results = searchService.search(project, resolvedQuery, resolvedLimit, explain);
            return new SearchOperation(
                    project, resolvedQuery, resolvedLimit, explain, elapsedMillis(startedAt), results);
        });
    }

    public FederatedSearchOperation searchAcrossProjects(
            List<UUID> projectIds,
            String query,
            int limit,
            boolean explain) throws IOException {
        List<UUID> scope = FederatedScopePolicy.normalizeProjectIds(projectIds);
        String resolvedQuery = requireQuery(query);
        int resolvedLimit = positiveLimit(limit);
        long startedAt = System.nanoTime();
        return readProjects(scope, (reads, projects) -> {
            FederatedSearchOutcome outcome =
                    federatedSearchService.searchWithDiagnostics(projects, resolvedQuery, resolvedLimit, explain);
            return new FederatedSearchOperation(
                    projects, resolvedQuery, resolvedLimit, explain, elapsedMillis(startedAt),
                    outcome.hits(), outcome.unservedProjects());
        });
    }

    public ContextOperation context(
            UUID projectId,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        String resolvedQuery = requireQuery(query);
        long startedAt = System.nanoTime();
        return readProject(projectId, (reads, project) -> {
            ContextBundle bundle = contextBuilder.build(new ContextRequest(
                    projectId,
                    resolvedQuery,
                    tokenBudget,
                    requestedSources == null ? Set.of() : requestedSources,
                    constraints == null ? Map.of() : constraints,
                    explain));
            return new ContextOperation(project, resolvedQuery, explain, elapsedMillis(startedAt), bundle);
        });
    }

    public FederatedContextOperation contextAcrossProjects(
            List<UUID> projectIds,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        List<UUID> scope = FederatedScopePolicy.normalizeProjectIds(projectIds);
        String resolvedQuery = requireQuery(query);
        long startedAt = System.nanoTime();
        return readProjects(scope, (reads, projects) -> {
            FederatedContextBundle bundle = federatedContextService.build(
                    projects,
                    resolvedQuery,
                    tokenBudget,
                    requestedSources == null ? Set.of() : requestedSources,
                    constraints == null ? Map.of() : constraints,
                    explain);
            return new FederatedContextOperation(
                    projects, resolvedQuery, explain, elapsedMillis(startedAt), bundle);
        });
    }

    public List<IndexedSymbol> findSymbols(UUID projectId, String query, int limit) {
        String resolvedQuery = requireQuery(query);
        int resolvedLimit = positiveLimit(limit);
        return readProject(projectId,
                (reads, project) -> indexRepository.searchSymbols(projectId, resolvedQuery, resolvedLimit));
    }

    public List<SymbolRelation> findUsages(UUID projectId, String symbol, int limit) {
        String resolvedSymbol = requireQuery(symbol);
        int resolvedLimit = positiveLimit(limit);
        return readProject(projectId,
                (reads, project) -> indexRepository.searchRelations(projectId, resolvedSymbol, resolvedLimit));
    }

    public ReadinessSnapshot readiness() {
        List<ProjectDescriptor> projects = projectRepository.findAll();
        EnumMap<IndexStatus, Integer> counts = new EnumMap<>(IndexStatus.class);
        for (IndexStatus status : IndexStatus.values()) {
            counts.put(status, 0);
        }
        projects.forEach(project -> counts.merge(project.indexStatus(), 1, Integer::sum));

        boolean derivedIndexesReady = projects.stream()
                .filter(project -> project.indexStatus() == IndexStatus.READY)
                .allMatch(project -> {
                    try {
                        return searchIndex.isPresent(project.id());
                    } catch (IOException | RuntimeException failure) {
                        return false;
                    }
                });
        boolean degraded = counts.get(IndexStatus.FAILED) > 0 || !derivedIndexesReady;
        boolean allProjectsReady = !projects.isEmpty() && projects.stream()
                .allMatch(project -> project.indexStatus() == IndexStatus.READY) && derivedIndexesReady;

        return new ReadinessSnapshot(
                true,
                allProjectsReady,
                degraded,
                projects.size(),
                counts,
                semanticSearchEnabled);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (semanticSearchIndex != null) {
            try {
                semanticSearchIndex.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        try {
            searchIndex.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Lecture cohérente d'un projet (DATA-0074) : READY, verrou partagé, session de
     * lecture, lecture sur le descripteur revalidé, puis revalidation READY avant de
     * rendre le résultat. Le verrou est libéré dans tous les cas.
     */
    private <T, E extends Exception> T readProject(
            UUID projectId,
            ConsistentRead<ProjectDescriptor, T, E> read) throws E {
        requireReadyProject(projectId);
        ProjectIndexLockManager.LockHandle readLock = projectIndexLockManager.acquireRead(projectId);
        try (IndexRepository.ReadSession reads = indexRepository.openReadSession()) {
            ProjectDescriptor project = requireReadyProject(projectId);
            T result = read.read(reads, project);
            requireReadyProject(projectId);
            return result;
        } finally {
            closeReadLock(readLock);
        }
    }

    /**
     * Lecture cohérente fédérée : même séquence que {@link #readProject}, verrous
     * partagés pris dans l'ordre des identifiants et libérés en ordre inverse.
     */
    private <T, E extends Exception> T readProjects(
            List<UUID> scope,
            ConsistentRead<List<ProjectDescriptor>, T, E> read) throws E {
        requireReadyProjects(scope);
        List<ProjectIndexLockManager.LockHandle> readLocks = acquireReadLocks(scope);
        try (IndexRepository.ReadSession reads = indexRepository.openReadSession()) {
            List<ProjectDescriptor> projects = requireReadyProjects(scope);
            T result = read.read(reads, projects);
            requireReadyProjects(scope);
            return result;
        } finally {
            closeReadLocks(readLocks);
        }
    }

    /** Lecture exécutée sous le gabarit de cohérence, sur les descripteurs revalidés. */
    @FunctionalInterface
    private interface ConsistentRead<S, T, E extends Exception> {
        T read(IndexRepository.ReadSession reads, S scope) throws E;
    }

    private ProjectDescriptor requireReadyProject(UUID projectId) {
        ProjectDescriptor project = getProject(projectId);
        com.nexus.project.ProjectReadiness.requireReady(project);
        return project;
    }

    private List<ProjectDescriptor> requireReadyProjects(List<UUID> projectIds) {
        Map<UUID, ProjectDescriptor> found = projectRepository.findByIds(projectIds).stream()
                .collect(java.util.stream.Collectors.toMap(ProjectDescriptor::id, project -> project));
        return projectIds.stream().map(projectId -> {
            ProjectDescriptor project = found.get(projectId);
            if (project == null) {
                throw new IllegalArgumentException("Projet NEXUS introuvable : " + projectId);
            }
            com.nexus.project.ProjectReadiness.requireReady(project);
            return project;
        }).toList();
    }

    private List<ProjectIndexLockManager.LockHandle> acquireReadLocks(List<UUID> projectIds) {
        // Acquisition et libération sur le même thread : les verrous locaux
        // sont réentrants et attachés au thread propriétaire.
        List<UUID> lockOrder = projectIds.stream().sorted().toList();
        List<ProjectIndexLockManager.LockHandle> locks = new ArrayList<>(lockOrder.size());
        try (ReadLockAcquisition acquisition = new ReadLockAcquisition(locks)) {
            for (UUID projectId : lockOrder) {
                locks.add(projectIndexLockManager.acquireRead(projectId));
            }
            return acquisition.transfer();
        }
    }

    /** Le try-with-resources conserve l'échec initial si la libération échoue aussi. */
    private static final class ReadLockAcquisition implements AutoCloseable {
        private final List<ProjectIndexLockManager.LockHandle> locks;
        private boolean transferred;

        private ReadLockAcquisition(List<ProjectIndexLockManager.LockHandle> locks) {
            this.locks = locks;
        }

        private List<ProjectIndexLockManager.LockHandle> transfer() {
            transferred = true;
            return locks;
        }

        @Override
        public void close() {
            if (!transferred) {
                closeReadLocks(locks);
            }
        }
    }

    private static void closeReadLocks(List<ProjectIndexLockManager.LockHandle> locks) {
        RuntimeException firstFailure = null;
        for (int index = locks.size() - 1; index >= 0; index--) {
            try {
                closeReadLock(locks.get(index));
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private static void closeReadLock(ProjectIndexLockManager.LockHandle lock) {
        try {
            lock.close();
        } catch (IOException failure) {
            throw new IllegalStateException("Impossible de libérer le verrou de lecture d'index", failure);
        }
    }

    private static String requireQuery(String value) {
        return QueryPolicy.normalize(value);
    }

    private static int positiveLimit(int limit) {
        return ResultLimitPolicy.validate(limit);
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    public record IndexOperation(ProjectDescriptor project, IndexingReport report) {
    }

    public record SearchOperation(
            ProjectDescriptor project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<RankedCandidate> results) {
        public SearchOperation {
            results = List.copyOf(results);
        }
    }

    public record FederatedSearchOperation(
            List<ProjectDescriptor> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchHit> results,
            List<ProjectDescriptor> unservedProjects) {
        public FederatedSearchOperation {
            projects = List.copyOf(projects);
            results = List.copyOf(results);
            unservedProjects = List.copyOf(unservedProjects);
        }
    }

    public record ContextOperation(
            ProjectDescriptor project,
            String query,
            boolean explain,
            long durationMs,
            ContextBundle bundle) {
    }

    public record FederatedContextOperation(
            List<ProjectDescriptor> projects,
            String query,
            boolean explain,
            long durationMs,
            FederatedContextBundle bundle) {
        public FederatedContextOperation {
            projects = List.copyOf(projects);
        }
    }

    public record ReadinessSnapshot(
            boolean operational,
            boolean allProjectsReady,
            boolean degraded,
            int registeredProjects,
            Map<IndexStatus, Integer> projectsByStatus,
            boolean semanticSearchEnabled) {
        public ReadinessSnapshot {
            projectsByStatus = Map.copyOf(projectsByStatus);
        }
    }
}
