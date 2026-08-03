package com.nexus.api;

import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import com.nexus.context.ContextBundle;
import com.nexus.context.FederatedContextBundle;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexingReport;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class NexusApiApplicationService {

    private final MeterRegistry meterRegistry;
    private final NexusApplication application;

    public NexusApiApplicationService(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.application = initializeApplication();
    }

    public List<ProjectDescriptor> listProjects() {
        return application.listProjects();
    }

    public ProjectDescriptor registerProject(java.nio.file.Path rootPath, String name) throws IOException {
        return application.registerProject(rootPath, name);
    }

    public ProjectDescriptor getProject(UUID projectId) {
        return application.getProject(projectId);
    }

    public IndexOperation index(UUID projectId, boolean rebuild, boolean deepJava) throws IOException {
        long startedAt = System.nanoTime();
        operationCounter("index");
        try {
            NexusApplication.IndexOperation operation = application.index(projectId, rebuild, deepJava);
            return new IndexOperation(operation.project(), operation.report());
        } finally {
            recordDuration("index", startedAt);
        }
    }

    public IndexStatistics inspect(UUID projectId) {
        return application.inspect(projectId);
    }

    public SearchOperation search(UUID projectId, String query, int limit, boolean explain) throws IOException {
        long startedAt = System.nanoTime();
        operationCounter("search");
        try {
            NexusApplication.SearchOperation operation = application.search(projectId, query, limit, explain);
            return new SearchOperation(
                    operation.project(), operation.query(), operation.limit(), operation.explain(),
                    operation.durationMs(), operation.results());
        } finally {
            recordDuration("search", startedAt);
        }
    }

    public FederatedSearchOperation searchAcrossProjects(
            List<UUID> projectIds,
            String query,
            int limit,
            boolean explain) throws IOException {
        long startedAt = System.nanoTime();
        operationCounter("search_federated");
        try {
            NexusApplication.FederatedSearchOperation operation =
                    application.searchAcrossProjects(projectIds, query, limit, explain);
            return new FederatedSearchOperation(
                    operation.projects(), operation.query(), operation.limit(), operation.explain(),
                    operation.durationMs(), operation.results());
        } finally {
            recordDuration("search_federated", startedAt);
        }
    }

    public ContextOperation context(
            UUID projectId,
            String query,
            int tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        long startedAt = System.nanoTime();
        operationCounter("context");
        try {
            NexusApplication.ContextOperation operation = application.context(
                    projectId, query, tokenBudget, parseRequestedSources(requestedSources),
                    constraints == null ? Map.of() : constraints, explain);
            return new ContextOperation(
                    operation.project(), operation.query(), operation.explain(),
                    operation.durationMs(), operation.bundle());
        } finally {
            recordDuration("context", startedAt);
        }
    }

    public FederatedContextOperation contextAcrossProjects(
            List<UUID> projectIds,
            String query,
            int tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        long startedAt = System.nanoTime();
        operationCounter("context_federated");
        try {
            NexusApplication.FederatedContextOperation operation = application.contextAcrossProjects(
                    projectIds, query, tokenBudget, parseRequestedSources(requestedSources),
                    constraints == null ? Map.of() : constraints, explain);
            return new FederatedContextOperation(
                    operation.projects(), operation.query(), operation.explain(),
                    operation.durationMs(), operation.bundle());
        } finally {
            recordDuration("context_federated", startedAt);
        }
    }

    public NexusApplication.ReadinessSnapshot readiness() {
        return application.readiness();
    }

    private static NexusApplication initializeApplication() {
        try {
            return NexusApplication.create(NexusPaths.fromEnvironment());
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Impossible d'initialiser NEXUS pour l'adaptateur REST", exception);
        }
    }

    private Set<CandidateType> parseRequestedSources(Set<String> requestedSources) {
        if (requestedSources == null || requestedSources.isEmpty()) {
            return Set.of();
        }
        return requestedSources.stream()
                .map(value -> {
                    try {
                        return CandidateType.valueOf(value.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Source de contexte inconnue : " + value, exception);
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    private void operationCounter(String operation) {
        meterRegistry.counter("nexus.api.operations", "operation", operation).increment();
    }

    private void recordDuration(String operation, long startedAt) {
        meterRegistry.timer("nexus.api.operation.duration", "operation", operation)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
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
            List<FederatedSearchHit> results) {
        public FederatedSearchOperation {
            projects = List.copyOf(projects);
            results = List.copyOf(results);
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
}
