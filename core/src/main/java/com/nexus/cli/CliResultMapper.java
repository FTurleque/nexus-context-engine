package com.nexus.cli;

import com.nexus.paths.RepositoryPath;

import com.nexus.context.ContextItem;
import com.nexus.index.CodeSymbol;
import com.nexus.index.IndexStatistics;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.security.PublicProjectPathPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Mapping des résultats CLI vers les contrats JSON publics. */
final class CliResultMapper {
    private static final String ESTIMATED_TOKENS_FIELD = "estimatedTokens";
    static List<Map<String, Object>> rankedResults(
            ProjectDescriptor project,
            List<RankedCandidate> results,
            boolean explain) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            maps.add(rankedResult(project, results.get(index), index + 1, explain));
        }
        return maps;
    }

    static Map<String, Object> rankedResult(
            ProjectDescriptor project,
            RankedCandidate ranked,
            int rank,
            boolean explain) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rank", rank);
        result.put("score", ranked.score());
        result.put("type", ranked.candidate().type().name());
        result.put("path", relativePath(project, ranked.candidate().path()));
        result.put("symbol", symbolMap(ranked.candidate().symbol()));
        result.put("scoreComponents", new TreeMap<>(ranked.components()));
        result.put("reasons", explain ? new com.nexus.security.PublicDiagnosticPolicy(List.of(project.rootPath())).texts(ranked.reasons()) : List.of());
        return result;
    }

    static Map<String, Object> contextItemMap(ContextItem item, boolean explain) {
        Map<String, Object> itemMap = new LinkedHashMap<>();
        itemMap.put("type", item.type().name());
        itemMap.put("path", repositoryPath(item.path()));
        itemMap.put("symbol", item.symbol());
        itemMap.put("startLine", item.startLine());
        itemMap.put("endLine", item.endLine());
        itemMap.put("content", item.content());
        itemMap.put("score", item.score());
        itemMap.put("scoreComponents", new TreeMap<>(item.scoreComponents()));
        itemMap.put("reasons", explain ? item.reasons() : List.of());
        itemMap.put(ESTIMATED_TOKENS_FIELD, item.estimatedTokens());
        itemMap.put("truncated", item.truncated());
        return itemMap;
    }

    static Map<String, Object> projectMap(ProjectDescriptor project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id().toString());
        map.put("name", project.name());
        map.put("rootPath", null);
        map.put("sourceType", project.sourceType().name());
        map.put("languages", project.languages().stream().sorted().toList());
        map.put("technologies", project.technologies().stream().sorted().toList());
        map.put("lastIndexedAt", project.lastIndexedAt() == null ? null : project.lastIndexedAt().toString());
        map.put("indexStatus", project.indexStatus().name());
        return map;
    }

    static Map<String, Object> statisticsMap(IndexStatistics statistics) {
        return Map.of("files", statistics.files(), "symbols", statistics.symbols(), "relations", statistics.relations());
    }

    static Map<String, Object> symbolMap(CodeSymbol symbol) {
        if (symbol == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", symbol.kind().name());
        map.put("name", symbol.name());
        map.put("qualifiedName", symbol.qualifiedName());
        map.put("signature", symbol.signature());
        map.put("startLine", symbol.startLine());
        map.put("endLine", symbol.endLine());
        return map;
    }

    static String relativePath(ProjectDescriptor project, Path path) {
        return PublicProjectPathPolicy.expose(project.rootPath(), path);
    }

    static String repositoryPath(Path path) {
        return RepositoryPath.encode(path);
    }
}
