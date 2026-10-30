package com.nexus.api;

import com.nexus.application.NexusApplication;
import com.nexus.exposure.PublicOutput;
import com.nexus.exposure.PublicOutputProjection;
import com.nexus.index.IndexStatistics;
import com.nexus.project.ProjectDescriptor;

/**
 * Passe les opérations de {@link NexusApplication} à la projection publique du cœur ; REST
 * sérialise ses structures sans les recomposer.
 */
final class ApiMapper {

    private ApiMapper() {
    }

    static PublicOutput.Project project(ProjectDescriptor project) {
        return PublicOutputProjection.project(project);
    }

    static PublicOutput.IndexStatistics statistics(IndexStatistics statistics) {
        return PublicOutputProjection.statistics(statistics);
    }

    static PublicOutput.Index index(NexusApplication.IndexOperation operation) {
        return PublicOutputProjection.index(operation.project(), operation.report());
    }

    static PublicOutput.Search search(NexusApplication.SearchOperation operation) {
        return PublicOutputProjection.search(operation.project(), operation.query(), operation.limit(),
                operation.explain(), operation.durationMs(), operation.results());
    }

    static PublicOutput.FederatedSearch federatedSearch(NexusApplication.FederatedSearchOperation operation) {
        return PublicOutputProjection.federatedSearch(operation.projects(), operation.query(), operation.limit(),
                operation.explain(), operation.durationMs(), operation.results());
    }

    static PublicOutput.Context context(NexusApplication.ContextOperation operation) {
        return PublicOutputProjection.context(operation.project(), operation.query(), operation.explain(),
                operation.durationMs(), operation.bundle());
    }

    static PublicOutput.FederatedContext federatedContext(NexusApplication.FederatedContextOperation operation) {
        return PublicOutputProjection.federatedContext(operation.projects(), operation.query(), operation.explain(),
                operation.durationMs(), operation.bundle());
    }
}
