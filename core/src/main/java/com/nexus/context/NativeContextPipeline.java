package com.nexus.context;

import com.nexus.context.source.ContextDiscoveryBudget;
import com.nexus.context.source.ContextSourceDiscoveryResult;
import com.nexus.context.source.ContextSourceDiscoveryService;
import com.nexus.context.source.ContextSourceFragmentFactory;
import com.nexus.context.source.ContextSourceProvider;
import com.nexus.context.source.ContextSourceQuery;
import com.nexus.context.source.NativeProjectCustomizationDetector;
import com.nexus.context.source.git.GitContextQuery;
import com.nexus.context.source.git.GitContextResult;
import com.nexus.context.source.git.GitContextSourceProvider;
import com.nexus.context.source.skill.SkillActivationResult;
import com.nexus.context.source.skill.SkillDiscoveryResult;
import com.nexus.context.source.skill.SkillDiscoveryService;
import com.nexus.context.source.skill.SkillLoader;
import com.nexus.context.source.skill.SkillMatch;
import com.nexus.context.source.skill.SkillSelector;
import com.nexus.context.source.skill.SkillSourceProvider;
import com.nexus.context.source.skill.SkillSourceQuery;
import com.nexus.project.ProjectDescriptor;
import com.nexus.search.CandidateType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.nexus.context.ContextPipelineState.NativeSources;

/** Étape interne et déterministe de construction du contexte. */
final class NativeContextPipeline {
    private static final int MIN_TOTAL_BUDGET_FOR_GIT = 500;
    private final List<ContextSourceProvider> sourceProviders;
    private final List<SkillSourceProvider> skillProviders;
    private final GitContextSourceProvider gitContextProvider;
    private final ContextSourceDiscoveryService sourceDiscoveryService;
    private final ContextSourceFragmentFactory sourceFragmentFactory;
    private final SkillDiscoveryService skillDiscoveryService;
    private final SkillSelector skillSelector;
    private final SkillLoader skillLoader;
    private final NativeProjectCustomizationDetector customizationDetector;

    NativeContextPipeline(List<ContextSourceProvider> sourceProviders,
            List<SkillSourceProvider> skillProviders, GitContextSourceProvider gitContextProvider) {
        this.sourceProviders = List.copyOf(Objects.requireNonNull(sourceProviders, "sourceProviders"));
        this.skillProviders = List.copyOf(Objects.requireNonNull(skillProviders, "skillProviders"));
        this.gitContextProvider = gitContextProvider;
        this.sourceDiscoveryService = new ContextSourceDiscoveryService();
        this.sourceFragmentFactory = new ContextSourceFragmentFactory();
        this.skillDiscoveryService = new SkillDiscoveryService();
        this.skillSelector = new SkillSelector();
        this.skillLoader = new SkillLoader();
        this.customizationDetector = new NativeProjectCustomizationDetector();
    }
    NativeSources discover(ContextRequest request, ProjectDescriptor project,
            List<Path> targetPaths, ContextDiscoveryBudget discoveryBudget) throws IOException {
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
        return new NativeSources(nativeDiscovery, instructionFragments, skillDiscovery,
                skillMatches, skillActivation, gitContext);
    }
    Map<String, List<String>> customizations(ProjectDescriptor project, ContextDiscoveryBudget budget) throws IOException {
        return customizationDetector.detect(project, budget);
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

    private static boolean sourceRequested(ContextRequest request, CandidateType type) {
        return request.requestedSources().isEmpty() || request.requestedSources().contains(type);
    }

}
