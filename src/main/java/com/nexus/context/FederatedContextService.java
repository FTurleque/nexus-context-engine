package com.nexus.context;

import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Construit un contexte fédéré sans faire fuiter les customisations natives
 * d'un projet vers un autre.
 *
 * <p>Chaque projet dispose d'un fair floor déterministe. Les builders locaux
 * reçoivent un budget candidat proportionnel à ce fair floor, avec un facteur
 * d'overfetch borné. Le coût de préparation local est donc O(budget global)
 * plutôt que O(nombre de projets × budget global). Après le premier tour
 * équitable et la déduplication, le budget libéré est redistribué globalement.</p>
 */
public final class FederatedContextService {

    public static final int MAX_FEDERATED_PROJECTS = 100;
    static final int LOCAL_OVERFETCH_FACTOR = 3;

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
        ContextBudgetPolicy.validate(tokenBudget);

        Map<UUID, ProjectDescriptor> unique = new LinkedHashMap<>();
        for (ProjectDescriptor project : projects) {
            ProjectDescriptor nonNull = Objects.requireNonNull(project, "project");
            unique.putIfAbsent(nonNull.id(), nonNull);
        }
        List<ProjectDescriptor> scope = List.copyOf(unique.values());
        if (scope.size() > MAX_FEDERATED_PROJECTS) {
            throw new IllegalArgumentException(
                    "federated scope must not exceed " + MAX_FEDERATED_PROJECTS + " projects");
        }
        if (tokenBudget < scope.size()) {
            throw new IllegalArgumentException(
                    "tokenBudget must be at least the number of projects in the federated scope");
        }

        int baseBudget = tokenBudget / scope.size();
        int remainder = tokenBudget % scope.size();
        List<List<FederatedContextItem>> perProjectItems = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Map<String, Integer> allocatedByProject = new LinkedHashMap<>();
        Map<String, Integer> candidateBudgetByProject = new LinkedHashMap<>();
        Map<String, Integer> localTokensByProject = new LinkedHashMap<>();
        Map<String, Integer> localItemsByProject = new LinkedHashMap<>();

        for (int index = 0; index < scope.size(); index++) {
            ProjectDescriptor project = scope.get(index);
            int fairAllocation = baseBudget + (index < remainder ? 1 : 0);
            int candidateBudget = Math.min(tokenBudget, fairAllocation * LOCAL_OVERFETCH_FACTOR);
            ContextBundle local = contextBuilder.build(new ContextRequest(
                    project.id(),
                    query,
                    candidateBudget,
                    requestedSources,
                    constraints,
                    explain));
            List<FederatedContextItem> items = local.items().stream()
                    .map(item -> new FederatedContextItem(project, item))
                    .toList();
            perProjectItems.add(items);
            allocatedByProject.put(project.id().toString(), fairAllocation);
            candidateBudgetByProject.put(project.id().toString(), candidateBudget);
            localTokensByProject.put(project.id().toString(), local.estimatedTokens());
            localItemsByProject.put(project.id().toString(), local.items().size());
            if (explain) {
                local.excluded().forEach(reason -> excluded.add(project.name() + ": " + reason));
            }
        }

        List<FederatedContextItem> selected = new ArrayList<>();
        List<FederatedContextItem> deferred = new ArrayList<>();
        Map<ContentKey, UUID> seen = new LinkedHashMap<>();
        Map<String, Integer> selectedTokensByProject = new LinkedHashMap<>();
        Map<String, Integer> selectedItemsByProject = new LinkedHashMap<>();
        boolean[] fairFloorClosed = new boolean[scope.size()];
        int[] crossProjectDuplicates = {0};
        int selectedTokens = 0;
        int maxItems = perProjectItems.stream().mapToInt(List::size).max().orElse(0);

        // Passe 1 : chaque projet peut consommer un préfixe de son ranking local
        // dans son fair floor. Dès que le prochain candidat ne tient plus, tous
        // les candidats suivants de ce projet sont différés afin de ne jamais
        // faire passer un résultat local moins bien classé devant lui.
        for (int itemIndex = 0; itemIndex < maxItems; itemIndex++) {
            for (int projectIndex = 0; projectIndex < scope.size(); projectIndex++) {
                List<FederatedContextItem> projectItems = perProjectItems.get(projectIndex);
                if (itemIndex >= projectItems.size()) {
                    continue;
                }
                FederatedContextItem federated = projectItems.get(itemIndex);
                ContextItem item = federated.item();
                String projectId = federated.project().id().toString();

                if (fairFloorClosed[projectIndex]) {
                    deferred.add(federated);
                    continue;
                }

                int fairAllocation = allocatedByProject.get(projectId);
                int projectTokens = selectedTokensByProject.getOrDefault(projectId, 0);
                if (projectTokens + item.estimatedTokens() > fairAllocation
                        || selectedTokens + item.estimatedTokens() > tokenBudget) {
                    fairFloorClosed[projectIndex] = true;
                    deferred.add(federated);
                    continue;
                }
                if (!retainUnique(federated, seen, excluded, explain, crossProjectDuplicates)) {
                    continue;
                }

                selected.add(federated);
                selectedTokens += item.estimatedTokens();
                selectedTokensByProject.merge(projectId, item.estimatedTokens(), Integer::sum);
                selectedItemsByProject.merge(projectId, 1, Integer::sum);
            }
        }

        // Passe 2 : les préfixes différés réutilisent le budget laissé libre par
        // les projets clairsemés ou par le dedup. L'ordre de deferred est issu du
        // round-robin de la passe 1 et conserve l'ordre relatif de chaque projet.
        int refillTokens = 0;
        int refillItems = 0;
        for (FederatedContextItem federated : deferred) {
            ContextItem item = federated.item();
            if (selectedTokens + item.estimatedTokens() > tokenBudget) {
                if (explain) {
                    excluded.add(federated.project().name() + ": " + item.path()
                            + " différé puis exclu : budget global restant insuffisant");
                }
                continue;
            }
            if (!retainUnique(federated, seen, excluded, explain, crossProjectDuplicates)) {
                continue;
            }

            selected.add(federated);
            selectedTokens += item.estimatedTokens();
            refillTokens += item.estimatedTokens();
            refillItems++;
            String projectId = federated.project().id().toString();
            selectedTokensByProject.merge(projectId, item.estimatedTokens(), Integer::sum);
            selectedItemsByProject.merge(projectId, 1, Integer::sum);
        }

        if (selectedTokens > tokenBudget) {
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
        metadata.put("candidateBudgetByProject", Map.copyOf(candidateBudgetByProject));
        metadata.put("candidateBudgetTotal", candidateBudgetByProject.values().stream().mapToInt(Integer::intValue).sum());
        metadata.put("candidateBudgetOverfetchFactor", LOCAL_OVERFETCH_FACTOR);
        metadata.put("localTokensByProject", Map.copyOf(localTokensByProject));
        metadata.put("localItemsByProject", Map.copyOf(localItemsByProject));
        metadata.put("selectedTokensByProject", Map.copyOf(selectedTokensByProject));
        metadata.put("selectedItemsByProject", Map.copyOf(selectedItemsByProject));
        metadata.put("refillTokens", refillTokens);
        metadata.put("refillItems", refillItems);
        metadata.put("unusedTokens", tokenBudget - selectedTokens);
        metadata.put("starvedProjects", starvedProjects);
        metadata.put("starvedProjectCount", starvedProjects.size());
        metadata.put("crossProjectDeduplicatedItems", crossProjectDuplicates[0]);
        metadata.put("mergePolicy", "fair-floor-bounded-overfetch-global-refill");
        metadata.put("nativeSourceScope", "project-local");

        return new FederatedContextBundle(
                selected,
                tokenBudget,
                selectedTokens,
                explain ? excluded : List.of(),
                metadata);
    }

    private static boolean retainUnique(
            FederatedContextItem federated,
            Map<ContentKey, UUID> seen,
            List<String> excluded,
            boolean explain,
            int[] crossProjectDuplicates) {
        ContextItem item = federated.item();
        ContentKey key = new ContentKey(item.type(), normalize(item.content()));
        UUID firstProject = seen.putIfAbsent(key, federated.project().id());
        if (firstProject == null) {
            return true;
        }
        if (!firstProject.equals(federated.project().id())) {
            crossProjectDuplicates[0]++;
        }
        if (explain) {
            excluded.add(federated.project().name() + ": " + item.path()
                    + " exclu : contenu identique déjà retenu");
        }
        return false;
    }

    private static String normalize(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    private record ContentKey(CandidateType type, String content) {
    }
}
