package com.nexus.context;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.context.source.ContextSourceDescriptor;
import com.nexus.context.source.ContextSourceDiscoveryResult;
import com.nexus.context.source.ContextSourceDiscoveryService;
import com.nexus.context.source.ContextSourceFragmentFactory;
import com.nexus.context.source.ContextSourceProvider;
import com.nexus.context.source.ContextSourceQuery;
import com.nexus.context.source.NativeProjectCustomizationDetector;
import com.nexus.context.source.git.GitContextQuery;
import com.nexus.context.source.git.GitContextResult;
import com.nexus.context.source.git.GitContextSourceProvider;
import com.nexus.context.source.skill.ActivatedSkill;
import com.nexus.context.source.skill.SkillActivationResult;
import com.nexus.context.source.skill.SkillContextSelector;
import com.nexus.context.source.skill.SkillDiscoveryResult;
import com.nexus.context.source.skill.SkillDiscoveryService;
import com.nexus.context.source.skill.SkillLoader;
import com.nexus.context.source.skill.SkillMatch;
import com.nexus.context.source.skill.SkillSelector;
import com.nexus.context.source.skill.SkillSourceProvider;
import com.nexus.context.source.skill.SkillSourceQuery;
import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.search.SearchService;
import com.nexus.token.TokenEstimator;

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
    private static final int MAX_SKILL_BUDGET = 2_000;
    private static final int MAX_GIT_BUDGET = 500;
    private static final int MIN_TOTAL_BUDGET_FOR_GIT = 500;

    private final ProjectRepository projectRepository;
    private final SearchService searchService;
    private final ContextFragmentFactory fragmentFactory;
    private final FragmentMerger fragmentMerger;
    private final BudgetedContextSelector contextSelector;
    private final TokenEstimator tokenEstimator;
    private final List<ContextSourceProvider> sourceProviders;
    private final List<SkillSourceProvider> skillProviders;
    private final GitContextSourceProvider gitContextProvider;
    private final ContextSourceDiscoveryService sourceDiscoveryService;
    private final ContextSourceFragmentFactory sourceFragmentFactory;
    private final SkillDiscoveryService skillDiscoveryService;
    private final SkillSelector skillSelector;
    private final SkillLoader skillLoader;
    private final SkillContextSelector skillContextSelector;
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
                List.of(),
                List.of(),
                null);
    }

    public DefaultContextBuilder(
            ProjectRepository projectRepository,
            SearchService searchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector,
            TokenEstimator tokenEstimator,
            List<ContextSourceProvider> sourceProviders) {
        this(
                projectRepository,
                searchService,
                fragmentFactory,
                fragmentMerger,
                contextSelector,
                tokenEstimator,
                sourceProviders,
                List.of(),
                null);
    }

    public DefaultContextBuilder(
            ProjectRepository projectRepository,
            SearchService searchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector,
            TokenEstimator tokenEstimator,
            List<ContextSourceProvider> sourceProviders,
            List<SkillSourceProvider> skillProviders) {
        this(
                projectRepository,
                searchService,
                fragmentFactory,
                fragmentMerger,
                contextSelector,
                tokenEstimator,
                sourceProviders,
                skillProviders,
                null);
    }

    public DefaultContextBuilder(
            ProjectRepository projectRepository,
            SearchService searchService,
            ContextFragmentFactory fragmentFactory,
            FragmentMerger fragmentMerger,
            BudgetedContextSelector contextSelector,
            TokenEstimator tokenEstimator,
            List<ContextSourceProvider> sourceProviders,
            List<SkillSourceProvider> skillProviders,
            GitContextSourceProvider gitContextProvider) {
        this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.fragmentFactory = Objects.requireNonNull(fragmentFactory, "fragmentFactory");
        this.fragmentMerger = Objects.requireNonNull(fragmentMerger, "fragmentMerger");
        this.contextSelector = Objects.requireNonNull(contextSelector, "contextSelector");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.sourceProviders = List.copyOf(Objects.requireNonNull(sourceProviders, "sourceProviders"));
        this.skillProviders = List.copyOf(Objects.requireNonNull(skillProviders, "skillProviders"));
        this.gitContextProvider = gitContextProvider;
        this.sourceDiscoveryService = new ContextSourceDiscoveryService();
        this.sourceFragmentFactory = new ContextSourceFragmentFactory();
        this.skillDiscoveryService = new SkillDiscoveryService();
        this.skillSelector = new SkillSelector();
        this.skillLoader = new SkillLoader();
        this.skillContextSelector = new SkillContextSelector(tokenEstimator);
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
            ContextDiscoveryBudget discoveryBudget = ContextDiscoveryLimits.fromEnvironment().newBudget();
            int retrievalLimit = retrievalLimit(request.tokenBudget());
            List<RankedCandidate> ranked = searchService.search(
                    project,
                    request.query(),
                    retrievalLimit,
                    request.explain());
            List<RankedCandidate> filtered = filterRequestedSources(request, ranked);
            List<Path> targetPaths = targetPaths(project, ranked);

            ContextSourceDiscoveryResult nativeDiscovery = discoverNativeSources(
                    request,
                    project,
                    targetPaths,
                    discoveryBudget);
            List<ContextFragment> instructionFragments = sourceFragmentFactory.create(nativeDiscovery.sources());

            SkillDiscoveryResult skillDiscovery = discoverSkills(request, project, discoveryBudget);
            List<SkillMatch> skillMatches = skillSelector.select(request.query(), skillDiscovery.skills());
            SkillActivationResult skillActivation = skillLoader.load(project, skillMatches, discoveryBudget);

            GitContextResult gitContext = discoverGitContext(
                    request,
                    project,
                    targetPaths,
                    discoveryBudget);

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

            int remainingAfterInstructions = Math.max(
                    0,
                    request.tokenBudget() - instructionSelection.selectedEstimatedTokens());
            int skillBudget = skillBudget(
                    request.tokenBudget(),
                    remainingAfterInstructions,
                    skillActivation.skills());
            ContextSelectionResult skillSelection = skillContextSelector.select(
                    skillActivation.skills(),
                    skillBudget,
                    request.explain());

            int remainingAfterSkills = Math.max(
                    0,
                    request.tokenBudget()
                            - instructionSelection.selectedEstimatedTokens()
                            - skillSelection.selectedEstimatedTokens());
            int gitBudget = gitBudget(request.tokenBudget(), remainingAfterSkills, gitContext);
            ContextSelectionResult gitSelection = selectOrEmpty(
                    gitContext.fragments(),
                    gitBudget,
                    request.explain(),
                    "contexte Git");

            int remainingBudget = Math.max(
                    0,
                    request.tokenBudget()
                            - instructionSelection.selectedEstimatedTokens()
                            - skillSelection.selectedEstimatedTokens()
                            - gitSelection.selectedEstimatedTokens());
            ContextSelectionResult taskSelection = selectOrEmpty(
                    mergedTaskFragments,
                    remainingBudget,
                    request.explain(),
                    "contexte de tâche");

            ContextSelectionResult combined = combineSelections(
                    instructionSelection,
                    skillSelection,
                    gitSelection,
                    taskSelection);
            Map<String, List<String>> nativeCustomizations = customizationDetector.detect(project, discoveryBudget);
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
                    skillDiscovery,
                    skillMatches,
                    skillActivation,
                    skillBudget,
                    skillSelection,
                    gitContext,
                    gitBudget,
                    gitSelection,
                    combined,
                    nativeCustomizations);
            Map<String, Object> boundedMetadata = new LinkedHashMap<>(metadata);
            boundedMetadata.put("nativeDiscoveryLimits", discoveryBudget.limits());
            boundedMetadata.put("nativeDiscoveryWork", discoveryBudget.snapshot());
            return new ContextBundle(
                    combined.items(),
                    request.tokenBudget(),
                    combined.selectedEstimatedTokens(),
                    request.explain() ? combined.excluded() : List.of(),
                    Map.copyOf(boundedMetadata));
        } catch (IOException exception) {
            throw new ContextBuildingException(
                    "Impossible de matérialiser le contexte du projet " + project.name(),
                    exception);
        }
    }

    private ContextSourceDiscoveryResult discoverNativeSources(
            ContextRequest request,
            ProjectDescriptor project,
            List<Path> targetPaths,
            ContextDiscoveryBudget discoveryBudget) throws IOException {
        if (!sourceRequested(request, CandidateType.INSTRUCTION) || sourceProviders.isEmpty()) {
            return new ContextSourceDiscoveryResult(List.of(), List.of());
        }
        return sourceDiscoveryService.discover(
                sourceProviders,
                new ContextSourceQuery(
                        project,
                        request.query(),
                        targetPaths,
                        request.explain(),
                        discoveryBudget));
    }

    private SkillDiscoveryResult discoverSkills(
            ContextRequest request,
            ProjectDescriptor project,
            ContextDiscoveryBudget discoveryBudget) throws IOException {
        if (!sourceRequested(request, CandidateType.SKILL) || skillProviders.isEmpty()) {
            return new SkillDiscoveryResult(List.of(), List.of(), List.of());
        }
        return skillDiscoveryService.discover(
                skillProviders,
                new SkillSourceQuery(project, request.explain(), discoveryBudget));
    }

    private GitContextResult discoverGitContext(
            ContextRequest request,
            ProjectDescriptor project,
            List<Path> targetPaths,
            ContextDiscoveryBudget discoveryBudget) throws IOException {
        if (!sourceRequested(request, CandidateType.GIT) || gitContextProvider == null) {
            return GitContextResult.disabled("provider Git absent ou source GIT non demandée");
        }
        if (request.tokenBudget() < MIN_TOTAL_BUDGET_FOR_GIT) {
            return GitContextResult.disabled("contexte Git désactivé pour un budget global inférieur à 500 tokens");
        }
        return gitContextProvider.discover(new GitContextQuery(
                project,
                request.query(),
                targetPaths,
                request.explain(),
                discoveryBudget));
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

    private static ContextSelectionResult combineSelections(ContextSelectionResult... selections) {
        List<ContextItem> items = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        int availableTokens = 0;
        int selectedTokens = 0;
        int truncatedItems = 0;

        for (ContextSelectionResult selection : selections) {
            items.addAll(selection.items());
            excluded.addAll(selection.excluded());
            availableTokens += selection.availableEstimatedTokens();
            selectedTokens += selection.selectedEstimatedTokens();
            truncatedItems += selection.truncatedItems();
        }
        return new ContextSelectionResult(
                items,
                excluded,
                availableTokens,
                selectedTokens,
                truncatedItems);
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

    private static int skillBudget(
            int totalBudget,
            int remainingBudget,
            List<ActivatedSkill> activatedSkills) {
        if (activatedSkills.isEmpty() || remainingBudget <= 0) {
            return 0;
        }
        int fifth = Math.max(64, totalBudget / 5);
        return Math.min(remainingBudget, Math.min(MAX_SKILL_BUDGET, fifth));
    }

    private static int gitBudget(
            int totalBudget,
            int remainingBudget,
            GitContextResult gitContext) {
        if (!gitContext.enabled()
                || !gitContext.repositoryAvailable()
                || gitContext.fragments().isEmpty()
                || remainingBudget <= 0
                || totalBudget < MIN_TOTAL_BUDGET_FOR_GIT) {
            return 0;
        }
        int fifteenPercent = Math.max(64, (totalBudget * 15) / 100);
        return Math.min(remainingBudget, Math.min(MAX_GIT_BUDGET, fifteenPercent));
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
            SkillDiscoveryResult skillDiscovery,
            List<SkillMatch> skillMatches,
            SkillActivationResult skillActivation,
            int skillBudget,
            ContextSelectionResult skillSelection,
            GitContextResult gitContext,
            int gitBudget,
            ContextSelectionResult gitSelection,
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
        metadata.put("skillProviders", skillProviders.stream().map(SkillSourceProvider::id).toList());
        metadata.put("skillsDiscovered", skillDiscovery.skills().size());
        metadata.put("skillsDeduplicated", skillDiscovery.deduplicatedSkills());
        metadata.put("skillDiagnostics", combinedSkillDiagnostics(skillDiscovery, skillActivation));
        metadata.put("skillsMatched", skillMatches.stream().map(match -> match.skill().name()).toList());
        metadata.put("skillsActivated", skillActivation.skills().stream()
                .map(skill -> skill.descriptor().name())
                .toList());
        metadata.put("skillResourcesDiscovered", skillDiscovery.skills().stream()
                .mapToInt(skill -> skill.resources().size())
                .sum());
        metadata.put("skillBudget", skillBudget);
        metadata.put("skillSelectedItems", skillSelection.items().size());
        metadata.put("skillSelectedTokens", skillSelection.selectedEstimatedTokens());
        metadata.put("skillsSelected", skillSelection.items().stream()
                .map(item -> repositoryPath(item.path()))
                .toList());
        metadata.put("skillsExecuted", false);
        metadata.put("gitProvider", gitContextProvider == null ? "" : gitContextProvider.id());
        metadata.put("gitEnabled", gitContext.enabled());
        metadata.put("gitRepositoryAvailable", gitContext.repositoryAvailable());
        metadata.put("gitDiagnostics", gitContext.diagnostics());
        metadata.put("gitCommitsInspected", gitContext.commitsInspected());
        metadata.put("gitRelatedCommits", gitContext.relatedCommits());
        metadata.put("gitCoChangeLinks", gitContext.coChangeLinks());
        metadata.put("gitBudget", gitBudget);
        metadata.put("gitSelectedItems", gitSelection.items().size());
        metadata.put("gitSelectedTokens", gitSelection.selectedEstimatedTokens());
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

    private static List<String> combinedSkillDiagnostics(
            SkillDiscoveryResult discovery,
            SkillActivationResult activation) {
        List<String> diagnostics = new ArrayList<>(discovery.diagnostics());
        diagnostics.addAll(activation.diagnostics());
        return List.copyOf(diagnostics);
    }

    private static String repositoryPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static double reductionRatio(int availableTokens, int selectedTokens) {
        if (availableTokens <= 0) {
            return 0.0d;
        }
        return Math.max(0.0d, 1.0d - ((double) selectedTokens / availableTokens));
    }
}
