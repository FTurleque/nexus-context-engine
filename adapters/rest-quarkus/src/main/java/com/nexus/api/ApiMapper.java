package com.nexus.api;

import com.nexus.api.ApiModels.ContextItemResponse;
import com.nexus.api.ApiModels.ContextResponse;
import com.nexus.api.ApiModels.FederatedContextItemResponse;
import com.nexus.api.ApiModels.FederatedContextResponse;
import com.nexus.api.ApiModels.FederatedSearchResponse;
import com.nexus.api.ApiModels.FederatedSearchResultResponse;
import com.nexus.api.ApiModels.IndexReportResponse;
import com.nexus.api.ApiModels.IndexResponse;
import com.nexus.api.ApiModels.IndexStatisticsResponse;
import com.nexus.api.ApiModels.ProjectResponse;
import com.nexus.api.ApiModels.SearchResponse;
import com.nexus.api.ApiModels.SearchResultResponse;
import com.nexus.api.ApiModels.SymbolResponse;
import com.nexus.context.ContextItem;
import com.nexus.context.FederatedContextItem;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexStatistics;
import com.nexus.index.IndexingReport;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.FederatedSearchHit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ApiMapper {

    private ApiMapper() {
    }

    static ProjectResponse project(ProjectDescriptor project) {
        return new ProjectResponse(
                project.id(),
                project.name(),
                normalize(project.rootPath()),
                project.sourceType().name(),
                project.languages(),
                project.technologies(),
                project.lastIndexedAt(),
                project.indexStatus().name());
    }

    static IndexStatisticsResponse statistics(IndexStatistics statistics) {
        return new IndexStatisticsResponse(statistics.files(), statistics.symbols(), statistics.relations());
    }

    static IndexResponse index(NexusApiApplicationService.IndexOperation operation) {
        IndexingReport report = operation.report();
        return new IndexResponse(
                project(operation.project()),
                new IndexReportResponse(
                        report.scannedFiles(),
                        report.changedFiles(),
                        report.removedFiles(),
                        report.skippedFiles(),
                        report.diagnostics(),
                        report.fullSearchRebuild(),
                        report.duration().toMillis(),
                        statistics(report.statistics())));
    }

    static SearchResponse search(NexusApiApplicationService.SearchOperation operation) {
        List<SearchResultResponse> results = new ArrayList<>(operation.results().size());
        for (int index = 0; index < operation.results().size(); index++) {
            results.add(searchResult(operation.project(), operation.results().get(index), index + 1));
        }
        return new SearchResponse(
                project(operation.project()),
                operation.query(),
                operation.limit(),
                operation.explain(),
                operation.durationMs(),
                List.copyOf(results));
    }

    static FederatedSearchResponse federatedSearch(NexusApiApplicationService.FederatedSearchOperation operation) {
        List<FederatedSearchResultResponse> results = new ArrayList<>(operation.results().size());
        for (int index = 0; index < operation.results().size(); index++) {
            FederatedSearchHit hit = operation.results().get(index);
            results.add(new FederatedSearchResultResponse(
                    project(hit.project()),
                    searchResult(hit.project(), hit.rankedCandidate(), index + 1)));
        }
        return new FederatedSearchResponse(
                operation.projects().stream().map(ApiMapper::project).toList(),
                operation.query(),
                operation.limit(),
                operation.explain(),
                operation.durationMs(),
                List.copyOf(results));
    }

    static ContextResponse context(NexusApiApplicationService.ContextOperation operation) {
        List<ContextItemResponse> items = operation.bundle().items().stream()
                .map(item -> contextItem(operation.project(), item))
                .toList();
        return new ContextResponse(
                project(operation.project()),
                operation.query(),
                operation.explain(),
                operation.durationMs(),
                operation.bundle().tokenBudget(),
                operation.bundle().estimatedTokens(),
                items,
                operation.bundle().excluded(),
                operation.bundle().metadata());
    }

    static FederatedContextResponse federatedContext(NexusApiApplicationService.FederatedContextOperation operation) {
        List<FederatedContextItemResponse> items = operation.bundle().items().stream()
                .map(item -> federatedContextItem(item))
                .toList();
        return new FederatedContextResponse(
                operation.projects().stream().map(ApiMapper::project).toList(),
                operation.query(),
                operation.explain(),
                operation.durationMs(),
                operation.bundle().tokenBudget(),
                operation.bundle().estimatedTokens(),
                items,
                operation.bundle().excluded(),
                operation.bundle().metadata());
    }

    private static SearchResultResponse searchResult(
            ProjectDescriptor project,
            RankedCandidate ranked,
            int rank) {
        CodeSymbol symbol = ranked.candidate().symbol();
        return new SearchResultResponse(
                rank,
                ranked.score(),
                ranked.candidate().type().name(),
                relativePath(project, ranked.candidate().path()),
                symbol == null ? null : symbol(symbol),
                ranked.components(),
                ranked.reasons());
    }

    private static SymbolResponse symbol(CodeSymbol symbol) {
        return new SymbolResponse(
                symbol.kind().name(),
                symbol.name(),
                symbol.qualifiedName(),
                symbol.signature(),
                symbol.startLine(),
                symbol.endLine(),
                symbol.sourceProvider());
    }

    private static FederatedContextItemResponse federatedContextItem(FederatedContextItem federated) {
        return new FederatedContextItemResponse(
                project(federated.project()),
                contextItem(federated.project(), federated.item()));
    }

    private static ContextItemResponse contextItem(ProjectDescriptor project, ContextItem item) {
        return new ContextItemResponse(
                item.type().name(),
                relativePath(project, item.path()),
                item.symbol(),
                item.startLine(),
                item.endLine(),
                item.content(),
                item.score(),
                item.scoreComponents(),
                item.reasons(),
                item.estimatedTokens(),
                item.truncated());
    }

    private static String relativePath(ProjectDescriptor project, Path path) {
        Path normalized = path.normalize();
        if (normalized.isAbsolute()) {
            Path root = project.rootPath().toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                normalized = root.relativize(normalized);
            }
        }
        return normalize(normalized);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
