package com.nexus.context;

import com.nexus.context.model.ContextItem;
import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.project.FederatedScopePolicy;
import com.nexus.project.FederationDeadline;
import com.nexus.project.FederationDeadlinePolicy;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.candidate.CandidateType;
import com.nexus.search.SearchQueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Construit un contexte fédéré sans faire fuiter les customisations natives
 * d'un projet vers un autre.
 *
 * <p>Chaque projet dispose d'un fair floor déterministe. Les builders locaux
 * reçoivent un budget candidat proportionnel à ce fair floor, avec un facteur
 * d'overfetch borné. Le coût de préparation local est donc O(budget global)
 * plutôt que O(nombre de projets × budget global). Après le premier tour
 * équitable et la déduplication, le budget libéré est redistribué globalement.</p>
 *
 * <p>{@link #build} enchaîne quatre étapes nommées : validation, collecte par
 * projet, sélection sous budget, métadonnées.</p>
 */
public final class FederatedContextService {

    /**
     * Alias de compatibilité conservé pour les intégrations existantes. La politique
     * canonique reste définie exclusivement par {@link FederatedScopePolicy#MAX_PROJECTS}.
     */
    public static final int MAX_FEDERATED_PROJECTS = FederatedScopePolicy.MAX_PROJECTS;

    static final int LOCAL_OVERFETCH_FACTOR = 3;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ContextBuilder contextBuilder;
    private final FederationDeadlinePolicy deadlinePolicy;
    private final ContextMaterializationLimits materializationLimits;
    private final ContextDiscoveryLimits discoveryLimits;

    public FederatedContextService(ContextBuilder contextBuilder) {
        this(contextBuilder, FederationDeadlinePolicy.defaults());
    }

    public FederatedContextService(ContextBuilder contextBuilder, FederationDeadlinePolicy deadlinePolicy) {
        this(contextBuilder, deadlinePolicy, ContextMaterializationLimits.defaults(), ContextDiscoveryLimits.defaults());
    }

    /** Composition de production : bornes globales de la requête fédérée résolues par la composition root. */
    public FederatedContextService(
            ContextBuilder contextBuilder,
            FederationDeadlinePolicy deadlinePolicy,
            ContextMaterializationLimits materializationLimits,
            ContextDiscoveryLimits discoveryLimits) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
        this.materializationLimits = Objects.requireNonNull(materializationLimits, "materializationLimits");
        this.discoveryLimits = Objects.requireNonNull(discoveryLimits, "discoveryLimits");
    }

    public FederatedContextBundle build(
            List<ProjectDescriptor> projects,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain) {
        List<ProjectDescriptor> scope = validate(projects, query, tokenBudget, requestedSources, constraints);
        ContextMaterializationBudget materializationBudget =
                materializationLimits.newBudget();
        ContextDiscoveryBudget discoveryBudget =
                discoveryLimits.newBudget();

        List<String> excluded = new ArrayList<>();
        ProjectCollection collection = collectPerProject(
                scope, query, tokenBudget, requestedSources, constraints, explain,
                materializationBudget, discoveryBudget, excluded);
        Selection selection = select(scope, collection, tokenBudget, explain, excluded);
        Map<String, Object> metadata = buildMetadata(
                scope, collection, selection, tokenBudget, discoveryBudget, materializationBudget);

        return new FederatedContextBundle(
                selection.selected(),
                tokenBudget,
                selection.selectedTokens(),
                explain ? excluded : List.of(),
                metadata,
                collection.unservedProjects());
    }

    /** Étape 1 : validation de la requête, du budget et du scope. */
    private static List<ProjectDescriptor> validate(
            List<ProjectDescriptor> projects,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(requestedSources, "requestedSources");
        Objects.requireNonNull(constraints, "constraints");
        List<ProjectDescriptor> scope = FederatedScopePolicy.normalizeProjects(projects);
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        ContextBudgetPolicy.validate(tokenBudget);
        if (tokenBudget < scope.size()) {
            throw new IllegalArgumentException(
                    "tokenBudget must be at least the number of projects in the federated scope");
        }
        return scope;
    }

    /**
     * Étape 2 : collecte par projet, dans l'ordre déterministe du scope, sous échéance
     * globale (BE-0081) : l'échéance est vérifiée avant chaque projet, sans parallélisme ;
     * un projet non commencé à l'échéance n'est pas servi et est nommé dans le diagnostic.
     */
    private ProjectCollection collectPerProject(
            List<ProjectDescriptor> scope,
            String query,
            int tokenBudget,
            Set<CandidateType> requestedSources,
            Map<String, String> constraints,
            boolean explain,
            ContextMaterializationBudget materializationBudget,
            ContextDiscoveryBudget discoveryBudget,
            List<String> excluded) {
        int baseBudget = tokenBudget / scope.size();
        int remainder = tokenBudget % scope.size();
        List<List<FederatedContextItem>> perProjectItems = new ArrayList<>();
        Map<String, Integer> allocatedByProject = new LinkedHashMap<>();
        Map<String, Integer> candidateBudgetByProject = new LinkedHashMap<>();
        Map<String, Integer> localTokensByProject = new LinkedHashMap<>();
        Map<String, Integer> localItemsByProject = new LinkedHashMap<>();
        // Contexte de requête unique : le vecteur de requête est calculé au plus une
        // fois pour tous les projets du scope (AUD-PRF-02).
        FederationDeadline deadline = deadlinePolicy.start();
        SearchQueryContext queryContext = SearchQueryContext.create(deadline);
        List<ProjectDescriptor> unservedProjects = new ArrayList<>();
        // Compteurs du cache de contexte Git : partagés par tous les projets, donc le dernier
        // relevé du parcours est le plus récent (BE-0070).
        Object gitContextCache = null;

        for (int index = 0; index < scope.size(); index++) {
            ProjectDescriptor project = scope.get(index);
            int fairAllocation = baseBudget + (index < remainder ? 1 : 0);
            int candidateBudget = Math.min(tokenBudget, fairAllocation * LOCAL_OVERFETCH_FACTOR);
            if (deadline.expired()) {
                unservedProjects.add(project);
                perProjectItems.add(List.of());
                allocatedByProject.put(project.id().toString(), fairAllocation);
                candidateBudgetByProject.put(project.id().toString(), 0);
                localTokensByProject.put(project.id().toString(), 0);
                localItemsByProject.put(project.id().toString(), 0);
                if (explain) {
                    excluded.add(project.name() + ": projet non servi : échéance globale de la fédération atteinte");
                }
                continue;
            }
            ContextBundle local = contextBuilder.build(
                    new ContextRequest(
                            project.id(),
                            query,
                            candidateBudget,
                            requestedSources,
                            constraints,
                            explain),
                    materializationBudget,
                    discoveryBudget,
                    queryContext);
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
                Object projectGitCache = local.metadata().get("gitContextCache");
                if (projectGitCache != null) {
                    gitContextCache = projectGitCache;
                }
            }
        }
        return new ProjectCollection(
                perProjectItems,
                allocatedByProject,
                candidateBudgetByProject,
                localTokensByProject,
                localItemsByProject,
                unservedProjects,
                gitContextCache);
    }

    /** Étape 3 : sélection sous budget (fair floor, déduplication, redistribution globale). */
    private static Selection select(
            List<ProjectDescriptor> scope,
            ProjectCollection collection,
            int tokenBudget,
            boolean explain,
            List<String> excluded) {
        SelectionAccumulator accumulator = new SelectionAccumulator(explain, excluded);
        List<FederatedContextItem> deferred = selectFairFloor(scope, collection, tokenBudget, accumulator);

        int refillTokens = 0;
        int refillItems = 0;
        for (FederatedContextItem federated : deferred) {
            ContextItem item = federated.item();
            if (!accumulator.fitsGlobalBudget(item, tokenBudget)) {
                if (explain) {
                    excluded.add(federated.project().name() + ": " + item.path()
                            + " différé puis exclu : budget global restant insuffisant");
                }
                continue;
            }
            if (!accumulator.retainUnique(federated)) {
                continue;
            }

            accumulator.accept(federated);
            refillTokens += item.estimatedTokens();
            refillItems++;
        }

        if (accumulator.selectedTokens > tokenBudget) {
            throw new IllegalStateException("Le contexte fédéré a dépassé son budget global");
        }
        return new Selection(
                accumulator.selected,
                accumulator.selectedTokens,
                accumulator.selectedTokensByProject,
                accumulator.selectedItemsByProject,
                refillTokens,
                refillItems,
                accumulator.crossProjectDuplicates[0]);
    }

    /**
     * Premier passage en tourniquet : chaque projet reçoit ses items dans l'ordre tant que son
     * plancher équitable et le budget global le permettent. Retourne les items différés.
     */
    private static List<FederatedContextItem> selectFairFloor(
            List<ProjectDescriptor> scope,
            ProjectCollection collection,
            int tokenBudget,
            SelectionAccumulator accumulator) {
        List<FederatedContextItem> deferred = new ArrayList<>();
        boolean[] fairFloorClosed = new boolean[scope.size()];
        int maxItems = collection.perProjectItems().stream().mapToInt(List::size).max().orElse(0);

        for (int itemIndex = 0; itemIndex < maxItems; itemIndex++) {
            for (int projectIndex = 0; projectIndex < scope.size(); projectIndex++) {
                List<FederatedContextItem> projectItems = collection.perProjectItems().get(projectIndex);
                if (itemIndex < projectItems.size()) {
                    offerFairShare(projectItems.get(itemIndex), projectIndex, fairFloorClosed, deferred,
                            collection, tokenBudget, accumulator);
                }
            }
        }
        return deferred;
    }

    /** Retient un item dans le plancher équitable de son projet, ou le diffère quand ce plancher est fermé. */
    private static void offerFairShare(
            FederatedContextItem federated,
            int projectIndex,
            boolean[] fairFloorClosed,
            List<FederatedContextItem> deferred,
            ProjectCollection collection,
            int tokenBudget,
            SelectionAccumulator accumulator) {
        if (fairFloorClosed[projectIndex]) {
            deferred.add(federated);
            return;
        }
        ContextItem item = federated.item();
        String projectId = federated.project().id().toString();
        int fairAllocation = collection.allocatedByProject().get(projectId);
        int projectTokens = accumulator.selectedTokensByProject.getOrDefault(projectId, 0);
        if (projectTokens + item.estimatedTokens() > fairAllocation
                || !accumulator.fitsGlobalBudget(item, tokenBudget)) {
            fairFloorClosed[projectIndex] = true;
            deferred.add(federated);
            return;
        }
        if (accumulator.retainUnique(federated)) {
            accumulator.accept(federated);
        }
    }

    /** État mutable de la sélection fédérée, partagé entre le plancher équitable et la redistribution. */
    private static final class SelectionAccumulator {
        private final List<FederatedContextItem> selected = new ArrayList<>();
        private final Map<ContentKey, UUID> seen = new LinkedHashMap<>();
        private final Map<String, Integer> selectedTokensByProject = new LinkedHashMap<>();
        private final Map<String, Integer> selectedItemsByProject = new LinkedHashMap<>();
        private final int[] crossProjectDuplicates = {0};
        private final boolean explain;
        private final List<String> excluded;
        private int selectedTokens;

        SelectionAccumulator(boolean explain, List<String> excluded) {
            this.explain = explain;
            this.excluded = excluded;
        }

        boolean retainUnique(FederatedContextItem federated) {
            return FederatedContextService.retainUnique(federated, seen, excluded, explain, crossProjectDuplicates);
        }

        boolean fitsGlobalBudget(ContextItem item, int tokenBudget) {
            return selectedTokens + item.estimatedTokens() <= tokenBudget;
        }

        void accept(FederatedContextItem federated) {
            int tokens = federated.item().estimatedTokens();
            String projectId = federated.project().id().toString();
            selected.add(federated);
            selectedTokens += tokens;
            selectedTokensByProject.merge(projectId, tokens, Integer::sum);
            selectedItemsByProject.merge(projectId, 1, Integer::sum);
        }
    }

    /** Étape 4 : métadonnées de la réponse fédérée. */
    private static Map<String, Object> buildMetadata(
            List<ProjectDescriptor> scope,
            ProjectCollection collection,
            Selection selection,
            int tokenBudget,
            ContextDiscoveryBudget discoveryBudget,
            ContextMaterializationBudget materializationBudget) {
        List<String> starvedProjects = scope.stream()
                .filter(project -> selection.selectedItemsByProject()
                        .getOrDefault(project.id().toString(), 0) == 0)
                .map(ProjectDescriptor::name)
                .toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectCount", scope.size());
        metadata.put("projectIds", scope.stream().map(project -> project.id().toString()).toList());
        metadata.put("allocationByProject", Map.copyOf(collection.allocatedByProject()));
        metadata.put("candidateBudgetByProject", Map.copyOf(collection.candidateBudgetByProject()));
        metadata.put("candidateBudgetTotal",
                collection.candidateBudgetByProject().values().stream().mapToInt(Integer::intValue).sum());
        metadata.put("candidateBudgetOverfetchFactor", LOCAL_OVERFETCH_FACTOR);
        metadata.put("localTokensByProject", Map.copyOf(collection.localTokensByProject()));
        metadata.put("localItemsByProject", Map.copyOf(collection.localItemsByProject()));
        metadata.put("selectedTokensByProject", Map.copyOf(selection.selectedTokensByProject()));
        metadata.put("selectedItemsByProject", Map.copyOf(selection.selectedItemsByProject()));
        metadata.put("refillTokens", selection.refillTokens());
        metadata.put("refillItems", selection.refillItems());
        metadata.put("unusedTokens", tokenBudget - selection.selectedTokens());
        metadata.put("starvedProjects", starvedProjects);
        metadata.put("starvedProjectCount", starvedProjects.size());
        metadata.put("crossProjectDeduplicatedItems", selection.crossProjectDuplicates());
        metadata.put("mergePolicy", "fair-floor-bounded-overfetch-global-refill");
        metadata.put("nativeSourceScope", "project-local");
        metadata.put("nativeDiscoveryLimits", discoveryBudget.limits());
        metadata.put("nativeDiscoveryWork", discoveryBudget.snapshot());
        metadata.put("taskMaterializationLimits", materializationBudget.limits());
        metadata.put("taskMaterializationWork", materializationBudget.snapshot());
        if (collection.gitContextCache() != null) {
            metadata.put("gitContextCache", collection.gitContextCache());
        }
        return metadata;
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
        return WHITESPACE.matcher(content).replaceAll(" ").trim();
    }

    private record ContentKey(CandidateType type, String content) {
    }

    private record ProjectCollection(
            List<List<FederatedContextItem>> perProjectItems,
            Map<String, Integer> allocatedByProject,
            Map<String, Integer> candidateBudgetByProject,
            Map<String, Integer> localTokensByProject,
            Map<String, Integer> localItemsByProject,
            List<ProjectDescriptor> unservedProjects,
            Object gitContextCache) {
    }

    private record Selection(
            List<FederatedContextItem> selected,
            int selectedTokens,
            Map<String, Integer> selectedTokensByProject,
            Map<String, Integer> selectedItemsByProject,
            int refillTokens,
            int refillItems,
            int crossProjectDuplicates) {
    }
}
