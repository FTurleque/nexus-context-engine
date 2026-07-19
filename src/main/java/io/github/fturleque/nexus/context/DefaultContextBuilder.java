package io.github.fturleque.nexus.context;

import io.github.fturleque.nexus.context.source.ContextSourceDescriptor;
import io.github.fturleque.nexus.context.source.ContextSourceDiscoveryResult;
import io.github.fturleque.nexus.context.source.ContextSourceDiscoveryService;
import io.github.fturleque.nexus.context.source.ContextSourceFragmentFactory;
import io.github.fturleque.nexus.context.source.ContextSourceProvider;
import io.github.fturleque.nexus.context.source.ContextSourceQuery;
import io.github.fturleque.nexus.context.source.NativeProjectCustomizationDetector;
import io.github.fturleque.nexus.project.IndexStatus;
import io.github.fturleque.nexus.project.ProjectDescriptor;
import io.github.fturleque.nexus.project.ProjectRepository;
import io.github.fturleque.nexus.ranking.RankedCandidate;
import io.github.fturleque.nexus.search.CandidateType;
import io.github.fturleque.nexus.search.SearchService;
import io.github.fturleque.nexus.token.TokenEstimator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Implémentation locale par défaut du pipeline de construction de contexte.
 */
public final class DefaultContextBuilder implements ContextBuilder {

    private static final int MIN_RETRIEVAL_LIMIT = 20;
    private static final int MAX_RETRIEVAL_LIMIT = 100;
    private static final int MAX_INSTRUCTION_BUDGET = 600;

    private final ProjectRepository projectRepository;
    private final SearchService searchService;
    private final ContextFragmentFactory fragmentFactory;
    private final FragmentMerger fragmentMerger;
    private final BudgetedContextSelector contextSelector;
    private final TokenEstimator tokenEstimator;
    private final List<ContextSourceProvider> sourceProviders;
    private final ContextSourceDiscoveryService sourceDiscoveryService;
    private final ContextSourceFragmentFactory sourceFragmentFactory;
    private final NativeProjectCustomizationDetector customizationDetector;

    public DefaultContextBuilder(
            ProjectRepository projectRepository,
            SearchService searchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector,
            TokenEstimator tokenEstimator) {
        this(
                projectRepository,
                searchService,
                fragmentFactory,
                fragmentMerger,
                contextSelector,
                tokenEstimator,
                List.of());
    }

    public DefaultContextBuilder(
            ProjectRepository projectRepository,
            SearchService searchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector,
            TokenEstimator tokenEstimator,
            List<ContextSourceProvider> sourceProviders) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.fragmentFactory = Objects.requireNonNull(fragmentFactory, "fragmentFactory");
        this.fragmentMerger = Objects.requireNonNull(fragmentMerger, "fragmentMerger");
        this.contextSelector = Objects.requireNonNull(contextSelector, "contextSelector");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.sourceProviders = List.copyOf(Objects.requireNonNull(sourceProviders, "sourceProviders"));
        this.sourceDiscoveryService = new ContextSourceDiscoveryService();
        this.sourceFragmentFactory = new ContextSourceFragmentFactory();
        this.customizationDetector = new NativeProjectCustomizationDetector();
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

            ContextSourceDiscoveryResult nativeDiscovery = discoverNativeSources(request, project, ranked);
            List<ContextFragment> instructionFragments = sourceFragmentFactory.create(nativeDiscovery.sources());

            List<ContextFragment> taskFragments = fragmentFactory.create(
                    project,
                    request.query(),
                    filtered,
                    request.tokenBudget());
            Set<Path> nativePaths = nativeDiscovery.sources().stream()
                    .map(ContextSourceDescriptor::path)
                    .map(Path::normalize)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<ContextFragment> deduplicatedTaskFragments = taskFragments.stream()
                    .filter(fragment -> !nativePaths.contains(fragment.path().normalize()))
                    .toList();
            int crossSourceDeduplicatedFragments = taskFragments.size() - deduplicatedTaskFragments.size();
            List<ContextFragment> mergedTaskFragments = fragmentMerger.merge(deduplicatedTaskFragments);

            int instructionBudget = instructionBudget(request.tokenBudget(), instructionFragments);
            ContextSelectionResult instructionSelection = selectOrEmpty(
                    instructionFragments,
                    instructionBudget,
                    request.explain(),
                    "instruction");

            int remainingBudget = Math.max(
                    0,
                    request.tokenBudget() - instructionSelection.selectedEstimatedTokens());
            ContextSelectionResult taskSelection = selectOrEmpty(
                    mergedTaskFragments,
                    remainingBudget,
                    request.explain(),
                    "contexte de tâche");

            ContextSelectionResult combined = combineSelections(instructionSelection, taskSelection);
            Map<String, List<String>> nativeCustomizations = customizationDetector.detect(project);
            Map<String, Object> metadata = metadata(
                    request,
                    ranked,
                    filtered,
                    taskFragments,
                    deduplicatedTaskFragments,
                    mergedTaskFragments,
                    nativeDiscovery,
                    crossSourceDeduplicatedFragments,
                    instructionBudget,
                    instructionSelection,
                    combined,
                    nativeCustomizations);
            return new ContextBundle(
                    combined.items(),
                    request.tokenBudget(),
                    combined.selectedEstimatedTokens(),
                    request.explain() ? combined.excluded() : List.of(),
                    metadata);
        } catch (IOException exception) {
            throw new ContextBuildingException(
                    "Impossible de matérialiser le contexte du projet " + project.name(),
                    exception);
        }
    }

    private ContextSourceDiscoveryResult discoverNativeSources(
            ContextRequest request,
            ProjectDescriptor project,
            List<RankedCandidate> ranked) throws IOException {
        if (!sourceRequested(request, CandidateType.INSTRUCTION) || sourceProviders.isEmpty()) {
            return new ContextSourceDiscoveryResult(List.of(), List.of());
        }
        List<Path> targetPaths = targetPaths(project, ranked);
        return sourceDiscoveryService.discover(
                sourceProviders,
                new ContextSourceQuery(project, request.query(), targetPaths, request.explain()));
    }

    private ContextSelectionResult selectOrEmpty(
            List<ContextFragment> fragments,
            int budget,
            boolean explain,
            String category) {
        if (fragments.isEmpty()) {
            return new ContextSelectionResult(List.of(), List.of(), 0, 0, 0);
        }
        if (budget > 0) {
            return contextSelector.select(fragments, budget, explain);
        }

        int available = fragments.stream()
                .mapToInt(fragment -> tokenEstimator.estimate(fragment.content()))
                .sum();
        List<String> excluded = explain
                ? fragments.stream()
                    .map(fragment -> fragment.path() + " exclu : budget épuisé pour " + category)
                    .toList()
                : List.of();
        return new ContextSelectionResult(List.of(), excluded, available, 0, 0);
    }

    private static ContextSelectionResult combineSelections(
            ContextSelectionResult instructions,
            ContextSelectionResult task) {
        List<ContextItem> items = new ArrayList<>(instructions.items());
        items.addAll(task.items());
        List<String> excluded = new ArrayList<>(instructions.excluded());
        excluded.addAll(task.excluded());
        return new ContextSelectionResult(
                items,
                excluded,
                instructions.availableEstimatedTokens() + task.availableEstimatedTokens(),
                instructions.selectedEstimatedTokens() + task.selectedEstimatedTokens(),
                instructions.truncatedItems() + task.truncatedItems());
    }

    private static List<Path> targetPaths(ProjectDescriptor project, List<RankedCandidate> ranked) {
        Set<Path> paths = new LinkedHashSet<>();
        Path root = project.rootPath().toAbsolutePath().normalize();
        for (RankedCandidate candidate : ranked) {
            Path absolute = candidate.candidate().path().toAbsolutePath().normalize();
            if (absolute.startsWith(root)) {
                paths.add(root.relativize(absolute));
            }
            if (paths.size() >= MAX_RETRIEVAL_LIMIT) {
                break;
            }
        }
        return List.copyOf(paths);
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

    private static boolean sourceRequested(ContextRequest request, CandidateType type) {
        return request.requestedSources().isEmpty() || request.requestedSources().contains(type);
    }

    private static int retrievalLimit(int tokenBudget) {
        return Math.min(MAX_RETRIEVAL_LIMIT,
                Math.max(MIN_RETRIEVAL_LIMIT, tokenBudget / 40));
    }

    private static int instructionBudget(int totalBudget, List<ContextFragment> instructionFragments) {
        if (instructionFragments.isEmpty()) {
            return 0;
        }
        int quarter = Math.max(24, totalBudget / 4);
        return Math.min(totalBudget, Math.min(MAX_INSTRUCTION_BUDGET, quarter));
    }

    private Map<String, Object> metadata(
            ContextRequest request,
            List<RankedCandidate> ranked,
            List<RankedCandidate> filtered,
            List<ContextFragment> taskFragments,
            List<ContextFragment> deduplicatedTaskFragments,
            List<ContextFragment> mergedTaskFragments,
            ContextSourceDiscoveryResult nativeDiscovery,
            int crossSourceDeduplicatedFragments,
            int instructionBudget,
            ContextSelectionResult instructionSelection,
            ContextSelectionResult combined,
            Map<String, List<String>> nativeCustomizations) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", request.query());
        metadata.put("tokenEstimator", tokenEstimator.toString());
        metadata.put("rankedCandidates", ranked.size());
        metadata.put("sourceEligibleCandidates", filtered.size());
        metadata.put("documentationCandidates", filtered.stream()
                .filter(candidate -> candidate.candidate().type() == CandidateType.DOCUMENTATION)
                .count());
        metadata.put("materializedFragments", taskFragments.size());
        metadata.put("crossSourceDeduplicatedFragments", crossSourceDeduplicatedFragments);
        metadata.put("taskFragmentsAfterCrossSourceDeduplication", deduplicatedTaskFragments.size());
        metadata.put("mergedFragments", mergedTaskFragments.size());
        metadata.put("instructionProviders", sourceProviders.stream().map(ContextSourceProvider::id).toList());
        metadata.put("nativeSourcesDiscovered", nativeDiscovery.sources().size());
        metadata.put("nativeSourcesDeduplicated", nativeDiscovery.deduplicatedSources());
        metadata.put("instructionBudget", instructionBudget);
        metadata.put("instructionSelectedItems", instructionSelection.items().size());
        metadata.put("instructionSelectedTokens", instructionSelection.selectedEstimatedTokens());
        metadata.put("nativeCustomizationsDetected", nativeCustomizations);
        metadata.put("selectedItems", combined.items().size());
        metadata.put("excludedItems", combined.excluded().size());
        metadata.put("truncatedItems", combined.truncatedItems());
        metadata.put("availableEstimatedTokens", combined.availableEstimatedTokens());
        metadata.put("selectedEstimatedTokens", combined.selectedEstimatedTokens());
        metadata.put("reductionRatio", reductionRatio(
                combined.availableEstimatedTokens(),
                combined.selectedEstimatedTokens()));
        return Map.copyOf(metadata);
    }

    private static double reductionRatio(int availableTokens, int selectedTokens) {
        if (availableTokens <= 0) {
            return 0.0d;
        }
        return Math.max(0.0d, 1.0d - ((double) selectedTokens / availableTokens));
    }
}
