package com.nexus.context;

import com.nexus.paths.RepositoryPath;

import com.nexus.context.source.ContextSourceProvider;
import com.nexus.context.source.git.GitContextSourceProvider;
import com.nexus.context.source.skill.SkillActivationResult;
import com.nexus.context.source.skill.SkillDiscoveryResult;
import com.nexus.context.source.skill.SkillSourceProvider;
import com.nexus.ranking.RankedCandidate;
import com.nexus.search.CandidateType;
import com.nexus.token.TokenEstimator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nexus.context.ContextPipelineState.NativeSources;
import com.nexus.context.ContextPipelineState.Selections;
import com.nexus.context.ContextPipelineState.TaskFragments;

/** Étape interne et déterministe de construction du contexte. */
final class ContextBuildMetadata {
    private final TokenEstimator tokenEstimator;
    private final List<ContextSourceProvider> sourceProviders;
    private final List<SkillSourceProvider> skillProviders;
    private final GitContextSourceProvider gitContextProvider;
    ContextBuildMetadata(TokenEstimator estimator, List<ContextSourceProvider> sources,
            List<SkillSourceProvider> skills, GitContextSourceProvider git) {
        tokenEstimator = estimator; sourceProviders = List.copyOf(sources);
        skillProviders = List.copyOf(skills); gitContextProvider = git;
    }
    Map<String, Object> metadata(ContextRequest request, List<RankedCandidate> ranked,
            List<RankedCandidate> filtered, NativeSources sources, TaskFragments task, Selections selections,
            Map<String, List<String>> nativeCustomizations) {
        var taskFragments = task.materialization().fragments();
        var deduplicatedTaskFragments = task.deduplicated();
        var mergedTaskFragments = task.merged();
        int crossSourceDeduplicatedFragments = task.duplicates();
        var nativeDiscovery = sources.nativeDiscovery();
        var skillDiscovery = sources.skillDiscovery();
        var skillMatches = sources.skillMatches();
        var skillActivation = sources.skillActivation();
        var gitContext = sources.gitContext();
        var instructionBudget = selections.instructionBudget();
        var instructionSelection = selections.instructionSelection();
        var skillBudget = selections.skillBudget();
        var skillSelection = selections.skillSelection();
        var gitBudget = selections.gitBudget();
        var gitSelection = selections.gitSelection();
        var combined = selections.combined();
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
        return RepositoryPath.encode(path);
    }

    private static double reductionRatio(int availableTokens, int selectedTokens) {
        if (availableTokens <= 0) {
            return 0.0d;
        }
        return Math.max(0.0d, 1.0d - ((double) selectedTokens / availableTokens));
    }}
