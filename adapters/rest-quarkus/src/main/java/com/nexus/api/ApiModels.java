package com.nexus.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApiModels {

    private ApiModels() {
    }

    public record CreateProjectRequest(String rootPath, String name) {
    }

    public record ProjectResponse(
            UUID id,
            String name,
            String rootPath,
            String sourceType,
            Set<String> languages,
            Set<String> technologies,
            Instant lastIndexedAt,
            String indexStatus) {
    }

    public record IndexStatisticsResponse(long files, long symbols, long relations) {
    }

    public record IndexReportResponse(
            int scannedFiles,
            int changedFiles,
            int removedFiles,
            int skippedFiles,
            List<String> diagnostics,
            boolean fullSearchRebuild,
            long durationMs,
            IndexStatisticsResponse statistics) {
    }

    public record IndexResponse(ProjectResponse project, IndexReportResponse report) {
    }

    public record SearchRequest(String query, Integer limit, Boolean explain) {
    }

    public record FederatedSearchRequest(
            List<UUID> projectIds,
            String query,
            Integer limit,
            Boolean explain) {
    }

    public record SymbolResponse(
            String kind,
            String name,
            String qualifiedName,
            String signature,
            int startLine,
            int endLine,
            String sourceProvider) {
    }

    public record SearchResultResponse(
            int rank,
            double score,
            String type,
            String path,
            SymbolResponse symbol,
            Map<String, Double> scoreComponents,
            List<String> reasons) {
    }

    public record SearchResponse(
            ProjectResponse project,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<SearchResultResponse> results) {
    }

    public record FederatedSearchResultResponse(
            ProjectResponse project,
            SearchResultResponse result) {
    }

    public record FederatedSearchResponse(
            List<ProjectResponse> projects,
            String query,
            int limit,
            boolean explain,
            long durationMs,
            List<FederatedSearchResultResponse> results) {
    }

    public record ContextRequestDto(
            String query,
            Integer tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            Boolean explain) {
    }

    public record FederatedContextRequest(
            List<UUID> projectIds,
            String query,
            Integer tokenBudget,
            Set<String> requestedSources,
            Map<String, String> constraints,
            Boolean explain) {
    }

    public record ContextItemResponse(
            String type,
            String path,
            String symbol,
            int startLine,
            int endLine,
            String content,
            double score,
            Map<String, Double> scoreComponents,
            List<String> reasons,
            int estimatedTokens,
            boolean truncated) {
    }

    public record ContextResponse(
            ProjectResponse project,
            String query,
            boolean explain,
            long durationMs,
            int tokenBudget,
            int estimatedTokens,
            List<ContextItemResponse> items,
            List<String> excluded,
            Map<String, Object> metadata) {
    }

    public record FederatedContextItemResponse(
            ProjectResponse project,
            ContextItemResponse item) {
    }

    public record FederatedContextResponse(
            List<ProjectResponse> projects,
            String query,
            boolean explain,
            long durationMs,
            int tokenBudget,
            int estimatedTokens,
            List<FederatedContextItemResponse> items,
            List<String> excluded,
            Map<String, Object> metadata) {
    }

    public record ErrorResponse(String error, String message) {
    }
}
