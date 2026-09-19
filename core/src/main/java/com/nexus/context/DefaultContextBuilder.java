package com.nexus.context;


import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextDiscoveryLimits;
import com.nexus.context.source.ContextSourceProvider;
import com.nexus.context.source.git.GitContextSourceProvider;
import com.nexus.context.source.skill.SkillSourceProvider;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectRepository;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.SearchService;
import com.nexus.token.TokenEstimator;

import java.io.IOException;
import java.nio.file.Path;
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

    private final ProjectRepository projectRepository;
    private final SearchService searchService;
    private final NativeContextPipeline nativePipeline;
    private final TaskContextMaterializer taskMaterializer;
    private final ContextBudgetAllocator budgetAllocator;
    private final ContextBuildMetadata buildMetadata;

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
        this.nativePipeline = new NativeContextPipeline(sourceProviders, skillProviders, gitContextProvider);
        this.taskMaterializer = new TaskContextMaterializer(Objects.requireNonNull(fragmentFactory, "fragmentFactory"),
                Objects.requireNonNull(fragmentMerger, "fragmentMerger"));
        this.budgetAllocator = new ContextBudgetAllocator(Objects.requireNonNull(contextSelector, "contextSelector"),
                Objects.requireNonNull(tokenEstimator, "tokenEstimator"));
        this.buildMetadata = new ContextBuildMetadata(tokenEstimator, sourceProviders, skillProviders, gitContextProvider);
    }

    @Override
    public ContextBundle build(ContextRequest request) {
        return build(request, ContextMaterializationLimits.fromEnvironment().newBudget());
    }

    @Override
    public ContextBundle build(
            ContextRequest request,
            ContextMaterializationBudget materializationBudget) {
        return build(
                request,
                materializationBudget,
                ContextDiscoveryLimits.fromEnvironment().newBudget());
    }

    @Override
    public ContextBundle build(
            ContextRequest request,
            ContextMaterializationBudget materializationBudget,
            ContextDiscoveryBudget discoveryBudget) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(materializationBudget, "materializationBudget");
        Objects.requireNonNull(discoveryBudget, "discoveryBudget");
        ProjectDescriptor project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ContextBuildingException(
                        "Projet introuvable : " + request.projectId()));
        try {
            com.nexus.project.ProjectReadiness.requireReady(project);
        } catch (IllegalStateException unavailable) {
            throw new ContextBuildingException("Le projet doit être indexé avant de construire un contexte", unavailable);
        }
        try {
            int retrievalLimit = retrievalLimit(request.tokenBudget());
            List<RankedCandidate> ranked = searchService.search(
                    project,
                    request.query(),
                    retrievalLimit,
                    request.explain());
            List<RankedCandidate> filtered = filterRequestedSources(request, ranked);
            List<Path> targetPaths = targetPaths(project, ranked);

            var nativeSources = nativePipeline.discover(request, project, targetPaths, discoveryBudget);
            var task = taskMaterializer.materialize(request, project, filtered,
                    nativeSources.nativeDiscovery(), materializationBudget);
            var selections = budgetAllocator.select(request, nativeSources, task.merged());
            var combined = selections.combined();
            var nativeCustomizations = nativePipeline.customizations(project, discoveryBudget);
            var metadata = buildMetadata.metadata(request, ranked, filtered, nativeSources, task,
                    selections, nativeCustomizations);
            Map<String, Object> boundedMetadata = new LinkedHashMap<>(metadata);
            boundedMetadata.put("nativeDiscoveryLimits", discoveryBudget.limits());
            boundedMetadata.put("nativeDiscoveryWork", discoveryBudget.snapshot());
            boundedMetadata.put("taskMaterializationLimits", materializationBudget.limits());
            boundedMetadata.put("taskMaterializationWork", materializationBudget.snapshot());
            boundedMetadata.put("taskMaterializationDiagnostics", task.materialization().diagnostics());
            var publicDiagnostics = new com.nexus.security.PublicDiagnosticPolicy(List.of(project.rootPath()));
            // La requête client n'est pas un diagnostic produit par NEXUS.
            boundedMetadata.remove("query");
            boundedMetadata = new LinkedHashMap<>(publicDiagnostics.metadata(boundedMetadata));
            boundedMetadata.put("query", request.query());
            return new ContextBundle(
                    combined.items(),
                    request.tokenBudget(),
                    combined.selectedEstimatedTokens(),
                    request.explain() ? publicDiagnostics.texts(combined.excluded()) : List.of(),
                    Map.copyOf(boundedMetadata));
        } catch (IOException exception) {
            throw new ContextBuildingException(
                    "Impossible de matérialiser le contexte du projet " + project.name(),
                    exception);
        }
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

    private static int retrievalLimit(int tokenBudget) {
        return Math.min(MAX_RETRIEVAL_LIMIT,
                Math.max(MIN_RETRIEVAL_LIMIT, tokenBudget / 40));
    }

}
