package com.nexus.context;

import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Construit un contexte fédéré sans faire fuiter les customisations natives
 * d'un projet vers un autre. Chaque projet reçoit une part déterministe du
 * budget global, puis les résultats sont entrelacés pour éviter la starvation.
 */
public final class FederatedContextService {

    private final ContextBuilder contextBuilder;

    public FederatedContextService(ContextBuilder contextBuilder) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    }

    public FederatedContextBundle build(
            List<ProjectDescriptor> projects,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        Objects.requireNonNull(projects, "projects");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(requestedSources, "requestedSources");
        Objects.requireNonNull(constraints, "constraints");
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("projects must not be empty");
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be greater than zero");
        }

        Map<UUID, ProjectDescriptor> unique = new LinkedHashMap<>();
        for (ProjectDescriptor project : projects) {
            ProjectDescriptor nonNull = Objects.requireNonNull(project, "project");
            unique.putIfAbsent(nonNull.id(), nonNull);
        }
        List<ProjectDescriptor> scope = List.copyOf(unique.values());
        if (tokenBudget < scope.size()) {
            throw new IllegalArgumentException(
                    "tokenBudget must be at least the number of projects in the federated scope");
        }

        int baseBudget = tokenBudget / scope.size();
        int remainder = tokenBudget % scope.size();
        List<List<FederatedContextItem>> perProjectItems = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Map<String, Integer> allocatedByProject = new LinkedHashMap<>();
        Map<String, Integer> localTokensByProject = new LinkedHashMap<>();
        Map<String, Integer> localItemsByProject = new LinkedHashMap<>();

        for (int index = 0; index < scope.size(); index++) {
            ProjectDescriptor project = scope.get(index);
            int projectBudget = baseBudget + (index < remainder ? 1 : 0);
            ContextBundle local = contextBuilder.build(new ContextRequest(
                    project.id(),
                    query,
                    projectBudget,
                    requestedSources,
                    constraints,
                    explain));
            List<FederatedContextItem> items = local.items().stream()
                    .map(item -> new FederatedContextItem(project, item))
                    .toList();
            perProjectItems.add(items);
            allocatedByProject.put(project.id().toString(), projectBudget);
            localTokensByProject.put(project.id().toString(), local.estimatedTokens());
            localItemsByProject.put(project.id().toString(), local.items().size());
            if (explain) {
                local.excluded().forEach(reason -> excluded.add(project.name() + ": " + reason));
            }
        }

        List<FederatedContextItem> selected = new ArrayList<>();
        Set<ContentKey> seen = new LinkedHashSet<>();
        Map<String, Integer> selectedTokensByProject = new LinkedHashMap<>();
        Map<String, Integer> selectedItemsByProject = new LinkedHashMap<>();
        int crossProjectDuplicates = 0;
        int maxItems = perProjectItems.stream().mapToInt(List::size).max().orElse(0);

        for (int itemIndex = 0; itemIndex < maxItems; itemIndex++) {
            for (int projectIndex = 0; projectIndex < scope.size(); projectIndex++) {
                List<FederatedContextItem> projectItems = perProjectItems.get(projectIndex);
                if (itemIndex >= projectItems.size()) {
                    continue;
                }
                FederatedContextItem federated = projectItems.get(itemIndex);
                ContextItem item = federated.item();
                ContentKey key = new ContentKey(item.type(), normalize(item.content()));
                if (!seen.add(key)) {
                    crossProjectDuplicates++;
                    if (explain) {
                        excluded.add(federated.project().name() + ": " + item.path()
                                + " exclu : contenu identique déjà retenu depuis un autre projet");
                    }
                    continue;
                }
                selected.add(federated);
                String projectId = federated.project().id().toString();
                selectedTokensByProject.merge(projectId, item.estimatedTokens(), Integer::sum);
                selectedItemsByProject.merge(projectId, 1, Integer::sum);
            }
        }

        int estimatedTokens = selected.stream()
                .map(FederatedContextItem::item)
                .mapToInt(ContextItem::estimatedTokens)
                .sum();
        if (estimatedTokens > tokenBudget) {
            throw new IllegalStateException("Le contexte fédéré a dépassé son budget global");
        }

        List<String> starvedProjects = scope.stream()
                .filter(project -> selectedItemsByProject.getOrDefault(project.id().toString(), 0) == 0)
                .map(ProjectDescriptor::name)
                .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectCount", scope.size());
        metadata.put("projectIds", scope.stream().map(project -> project.id().toString()).toList());
        metadata.put("allocationByProject", Map.copyOf(allocatedByProject));
        metadata.put("localTokensByProject", Map.copyOf(localTokensByProject));
        metadata.put("localItemsByProject", Map.copyOf(localItemsByProject));
        metadata.put("selectedTokensByProject", Map.copyOf(selectedTokensByProject));
        metadata.put("selectedItemsByProject", Map.copyOf(selectedItemsByProject));
        metadata.put("starvedProjects", starvedProjects);
        metadata.put("starvedProjectCount", starvedProjects.size());
        metadata.put("crossProjectDeduplicatedItems", crossProjectDuplicates);
        metadata.put("mergePolicy", "fair-budget-round-robin");
        metadata.put("nativeSourceScope", "project-local");

        return new FederatedContextBundle(
                selected,
                tokenBudget,
                estimatedTokens,
                explain ? excluded : List.of(),
                metadata);
    }

    private static String normalize(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    private record ContentKey(CandidateType type, String content) {
    }
}
