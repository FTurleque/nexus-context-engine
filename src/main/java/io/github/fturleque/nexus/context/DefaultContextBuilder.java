package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.project.IndexStatus;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.ranking.RankedCandidate;
import io.github.fturleque.nexus.search.SearchService;
import io.github.fturleque.nexus.token.TokenEstimator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implémentation locale par défaut du pipeline de construction de contexte.
 */
public final class DefaultContextBuilder implements ContextBuilder {

    private static final int MIN_RETRIEVAL_LIMIT = 20;
    private static final int MAX_RETRIEVAL_LIMIT = 100;

    private final ProjectRepository projectRepository;
    private final SearchService searchService;
    private final ContextFragmentFactory fragmentFactory;
    private final FragmentMerger fragmentMerger;
    private final BudgetedContextSelector contextSelector;
    private final TokenEstimator tokenEstimator;

    public DefaultContextBuilder(
            ProjectRepository projectRepository,
            SearchService searchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector,
            TokenEstimator tokenEstimator) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.fragmentFactory = Objects.requireNonNull(fragmentFactory, "fragmentFactory");
        this.fragmentMerger = Objects.requireNonNull(fragmentMerger, "fragmentMerger");
        this.contextSelector = Objects.requireNonNull(contextSelector, "contextSelector");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    @Override
    public ContextBundle build(ContextRequest request) {
        Objects.requireNonNull(request, "request");
        ProjectDescriptor project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ContextBuildingException(
                        "Projet introuvable : " + request.projectId()));
        if (project.indexStatus() != IndexStatus.READY) {
            throw new ContextBuildingException(
                    "Le projet doit être indexé avant de construire un contexte : " + project.name());
        }

        try {
            int retrievalLimit = retrievalLimit(request.tokenBudget());
            List<RankedCandidate> ranked = searchService.search(
                    project,
                    request.query(),
                    retrievalLimit,
                    request.explain());
            List<RankedCandidate> filtered = filterRequestedSources(request, ranked);
            List<ContextFragment> fragments = fragmentFactory.create(
                    project,
                    request.query(),
                    filtered,
                    request.tokenBudget());
            List<ContextFragment> merged = fragmentMerger.merge(fragments);
            ContextSelectionResult selection = contextSelector.select(
                    merged,
                    request.tokenBudget(),
                    request.explain());

            Map<String, Object> metadata = metadata(
                    request,
                    ranked.size(),
                    filtered.size(),
                    fragments.size(),
                    merged.size(),
                    selection);
            return new ContextBundle(
                    selection.items(),
                    request.tokenBudget(),
                    selection.selectedEstimatedTokens(),
                    request.explain() ? selection.excluded() : List.of(),
                    metadata);
        } catch (IOException exception) {
            throw new ContextBuildingException(
                    "Impossible de matérialiser le contexte du projet " + project.name(),
                    exception);
        }
    }

    private static List<RankedCandidate> filterRequestedSources(
            ContextRequest request,
            List<RankedCandidate> ranked) {
        if (request.requestedSources().isEmpty()) {
            return ranked;
        }
        return ranked.stream()
                .filter(candidate -> request.requestedSources().contains(candidate.candidate().type()))
                .toList();
    }

    private static int retrievalLimit(int tokenBudget) {
        return Math.min(MAX_RETRIEVAL_LIMIT,
                Math.max(MIN_RETRIEVAL_LIMIT, tokenBudget / 40));
    }

    private Map<String, Object> metadata(
            ContextRequest request,
            int rankedCandidates,
            int filteredCandidates,
            int fragments,
            int mergedFragments,
            ContextSelectionResult selection) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", request.query());
        metadata.put("tokenEstimator", tokenEstimator.toString());
        metadata.put("rankedCandidates", rankedCandidates);
        metadata.put("sourceEligibleCandidates", filteredCandidates);
        metadata.put("materializedFragments", fragments);
        metadata.put("mergedFragments", mergedFragments);
        metadata.put("selectedItems", selection.items().size());
        metadata.put("excludedItems", selection.excluded().size());
        metadata.put("truncatedItems", selection.truncatedItems());
        metadata.put("availableEstimatedTokens", selection.availableEstimatedTokens());
        metadata.put("selectedEstimatedTokens", selection.selectedEstimatedTokens());
        metadata.put("reductionRatio", reductionRatio(
                selection.availableEstimatedTokens(),
                selection.selectedEstimatedTokens()));
        return Map.copyOf(metadata);
    }

    private static double reductionRatio(int availableTokens, int selectedTokens) {
        if (availableTokens <= 0) {
            return 0.0d;
        }
        return Math.max(0.0d, 1.0d - ((double) selectedTokens / availableTokens));
    }
}
