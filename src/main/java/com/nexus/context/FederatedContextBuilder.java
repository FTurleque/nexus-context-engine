package com.nexus.context;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.FederatedSearchHit;
import com.nexus.search.FederatedSearchService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Construit un contexte de tâche sur plusieurs projets à partir de la recherche
 * fédérée existante, puis applique un unique budget de tokens global.
 *
 * <p>Ce premier incrément reste volontairement limité aux sources de tâche
 * ({@code FILE}, {@code SYMBOL}, {@code TEST}, {@code DOCUMENTATION}). Les
 * instructions, skills et signaux Git restent projet-locaux jusqu'à ce qu'une
 * politique de priorité et de budget soit mesurée explicitement.</p>
 */
public final class FederatedContextBuilder {

    private static final int MIN_RETRIEVAL_LIMIT = 20;
    private static final int MAX_RETRIEVAL_LIMIT = 100;
    private static final Set<CandidateType> SUPPORTED_TASK_SOURCES = Set.of(
            CandidateType.FILE,
            CandidateType.SYMBOL,
            CandidateType.TEST,
            CandidateType.DOCUMENTATION);
    private static final Set<CandidateType> DEFERRED_PROJECT_LOCAL_SOURCES = Set.of(
            CandidateType.INSTRUCTION,
            CandidateType.SKILL,
            CandidateType.GIT);

    private final FederatedSearchService federatedSearchService;
    private final ContextFragmentFactory fragmentFactory;
    private final FragmentMerger fragmentMerger;
    private final BudgetedContextSelector contextSelector;

    public FederatedContextBuilder(
            FederatedSearchService federatedSearchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector) {
        this.federatedSearchService = Objects.requireNonNull(federatedSearchService, "federatedSearchService");
        this.fragmentFactory = Objects.requireNonNull(fragmentFactory, "fragmentFactory");
        this.fragmentMerger = Objects.requireNonNull(fragmentMerger, "fragmentMerger");
        this.contextSelector = Objects.requireNonNull(contextSelector, "contextSelector");
    }

    public FederatedContextBundle build(FederatedContextRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequestedSources(request.requestedSources());
        List<ProjectDescriptor> projects = uniqueProjects(request.projects());
        validateProjectsReady(projects);

        try {
            int retrievalLimit = retrievalLimit(request.tokenBudget());
            List<FederatedSearchHit> ranked = federatedSearchService.search(
                    projects,
                    request.query(),
                    retrievalLimit,
                    request.explain());
            List<FederatedSearchHit> eligible = filterRequestedSources(request, ranked);

            Map<UUID, List<RankedCandidate>> candidatesByProject = new LinkedHashMap<>();
            for (FederatedSearchHit hit : eligible) {
                candidatesByProject
                        .computeIfAbsent(hit.project().id(), ignored -> new ArrayList<>())
                        .add(hit.rankedCandidate());
            }

            List<ContextFragment> scopedFragments = new ArrayList<>();
            int materializedFragments = 0;
            for (ProjectDescriptor project : projects) {
                List<RankedCandidate> projectCandidates = candidatesByProject.getOrDefault(project.id(), List.of());
                if (projectCandidates.isEmpty()) {
                    continue;
                }
                List<ContextFragment> fragments = fragmentFactory.create(
                        project,
                        request.query(),
                        projectCandidates,
                        request.tokenBudget());
                materializedFragments += fragments.size();
                for (ContextFragment fragment : fragmentMerger.merge(fragments)) {
                    scopedFragments.add(scope(project, fragment));
                }
            }

            ContextSelectionResult selection = contextSelector.select(
                    scopedFragments,
                    request.tokenBudget(),
                    request.explain());
            Map<UUID, ProjectDescriptor> projectsById = byId(projects);
            List<FederatedContextItem> items = selection.items().stream()
                    .map(item -> unscopedItem(item, projectsById))
                    .toList();
            List<String> excluded = request.explain()
                    ? selection.excluded().stream()
                        .map(value -> unscopedExclusion(value, projects))
                        .toList()
                    : List.of();

            return new FederatedContextBundle(
                    items,
                    request.tokenBudget(),
                    selection.selectedEstimatedTokens(),
                    excluded,
                    metadata(
                            request,
                            projects,
                            ranked,
                            eligible,
                            materializedFragments,
                            scopedFragments.size(),
                            selection,
                            items));
        } catch (IOException exception) {
            throw new ContextBuildingException("Impossible de matérialiser le contexte fédéré", exception);
        }
    }

    private static void validateRequestedSources(Set<CandidateType> requestedSources) {
        List<CandidateType> unsupported = requestedSources.stream()
                .filter(DEFERRED_PROJECT_LOCAL_SOURCES::contains)
                .sorted()
                .toList();
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                    "Le contexte fédéré ne prend pas encore en charge les sources projet-locales : " + unsupported);
        }
    }

    private static List<ProjectDescriptor> uniqueProjects(List<ProjectDescriptor> projects) {
        Map<UUID, ProjectDescriptor> unique = new LinkedHashMap<>();
        for (ProjectDescriptor project : projects) {
            ProjectDescriptor nonNullProject = Objects.requireNonNull(project, "project");
            unique.putIfAbsent(nonNullProject.id(), nonNullProject);
        }
        return List.copyOf(unique.values());
    }

    private static void validateProjectsReady(List<ProjectDescriptor> projects) {
        for (ProjectDescriptor project : projects) {
            if (project.indexStatus() != IndexStatus.READY) {
                throw new ContextBuildingException(
                        "Le projet doit être indexé avant de construire un contexte fédéré : " + project.name());
            }
        }
    }

    private static List<FederatedSearchHit> filterRequestedSources(
            FederatedContextRequest request,
            List<FederatedSearchHit> ranked) {
        if (request.requestedSources().isEmpty()) {
            return ranked.stream()
                    .filter(hit -> SUPPORTED_TASK_SOURCES.contains(hit.rankedCandidate().candidate().type()))
                    .toList();
        }
        return ranked.stream()
                .filter(hit -> request.requestedSources().contains(hit.rankedCandidate().candidate().type()))
                .toList();
    }

    private static ContextFragment scope(ProjectDescriptor project, ContextFragment fragment) {
        if (fragment.path().isAbsolute()) {
            throw new ContextBuildingException("Un fragment fédéré doit conserver un chemin relatif : " + fragment.path());
        }
        Path scopedPath = Path.of(project.id().toString()).resolve(fragment.path());
        return new ContextFragment(
                fragment.type(),
                scopedPath,
                fragment.symbol(),
                fragment.startLine(),
                fragment.endLine(),
                fragment.content(),
                fragment.score(),
                fragment.scoreComponents(),
                fragment.reasons());
    }

    private static FederatedContextItem unscopedItem(
            ContextItem item,
            Map<UUID, ProjectDescriptor> projectsById) {
        if (item.path().getNameCount() < 2) {
            throw new ContextBuildingException("Chemin de sélection fédérée invalide : " + item.path());
        }
        UUID projectId;
        try {
            projectId = UUID.fromString(item.path().getName(0).toString());
        } catch (IllegalArgumentException exception) {
            throw new ContextBuildingException("Provenance fédérée invalide : " + item.path(), exception);
        }
        ProjectDescriptor project = projectsById.get(projectId);
        if (project == null) {
            throw new ContextBuildingException("Projet de provenance introuvable : " + projectId);
        }
        Path relativePath = item.path().subpath(1, item.path().getNameCount());
        ContextItem unscoped = new ContextItem(
                item.type(),
                relativePath,
                item.symbol(),
                item.startLine(),
                item.endLine(),
                item.content(),
                item.score(),
                item.scoreComponents(),
                item.reasons(),
                item.estimatedTokens(),
                item.truncated());
        return new FederatedContextItem(project, unscoped);
    }

    private static String unscopedExclusion(String value, List<ProjectDescriptor> projects) {
        for (ProjectDescriptor project : projects) {
            String prefix = project.id() + File.separator;
            if (value.startsWith(prefix)) {
                return project.name() + ":" + value.substring(prefix.length());
            }
        }
        return value;
    }

    private static Map<UUID, ProjectDescriptor> byId(List<ProjectDescriptor> projects) {
        Map<UUID, ProjectDescriptor> result = new LinkedHashMap<>();
        for (ProjectDescriptor project : projects) {
            result.put(project.id(), project);
        }
        return Map.copyOf(result);
    }

    private static int retrievalLimit(int tokenBudget) {
        return Math.min(MAX_RETRIEVAL_LIMIT,
                Math.max(MIN_RETRIEVAL_LIMIT, tokenBudget / 40));
    }

    private static Map<String, Object> metadata(
            FederatedContextRequest request,
            List<ProjectDescriptor> projects,
            List<FederatedSearchHit> ranked,
            List<FederatedSearchHit> eligible,
            int materializedFragments,
            int mergedFragments,
            ContextSelectionResult selection,
            List<FederatedContextItem> items) {
        Map<String, Integer> selectedItemsByProject = new LinkedHashMap<>();
        Map<String, Integer> selectedTokensByProject = new LinkedHashMap<>();
        for (FederatedContextItem item : items) {
            String projectId = item.project().id().toString();
            selectedItemsByProject.merge(projectId, 1, Integer::sum);
            selectedTokensByProject.merge(projectId, item.item().estimatedTokens(), Integer::sum);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", request.query());
        metadata.put("projects", projects.stream()
                .map(project -> project.id() + ":" + project.name())
                .toList());
        metadata.put("projectCount", projects.size());
        metadata.put("rankedCandidates", ranked.size());
        metadata.put("sourceEligibleCandidates", eligible.size());
        metadata.put("materializedFragments", materializedFragments);
        metadata.put("mergedFragments", mergedFragments);
        metadata.put("selectedItems", items.size());
        metadata.put("availableEstimatedTokens", selection.availableEstimatedTokens());
        metadata.put("selectedEstimatedTokens", selection.selectedEstimatedTokens());
        metadata.put("truncatedItems", selection.truncatedItems());
        metadata.put("selectedItemsByProject", Map.copyOf(selectedItemsByProject));
        metadata.put("selectedTokensByProject", Map.copyOf(selectedTokensByProject));
        metadata.put("budgetPolicy", "global-ranking-no-static-project-quota");
        metadata.put("crossProjectDeduplication", false);
        metadata.put("projectLocalSourcesIncluded", false);
        metadata.put("supportedSources", SUPPORTED_TASK_SOURCES.stream().map(Enum::name).sorted().toList());
        metadata.put("deferredProjectLocalSources",
                DEFERRED_PROJECT_LOCAL_SOURCES.stream().map(Enum::name).sorted().toList());
        metadata.put("constraints", request.constraints());
        return Map.copyOf(metadata);
    }
}
