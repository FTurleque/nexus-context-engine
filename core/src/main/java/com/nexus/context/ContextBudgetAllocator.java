package com.nexus.context;

import com.nexus.context.source.git.GitContextResult;
import com.nexus.context.source.skill.ActivatedSkill;
import com.nexus.context.source.skill.SkillContextSelector;
import com.nexus.token.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

import com.nexus.context.ContextPipelineState.NativeSources;
import com.nexus.context.ContextPipelineState.Selections;

/** Étape interne et déterministe de construction du contexte. */
final class ContextBudgetAllocator {
    private static final int MAX_INSTRUCTION_BUDGET = 600;
    private static final int MAX_SKILL_BUDGET = 2_000;
    private static final int MAX_GIT_BUDGET = 500;
    private static final int MIN_TOTAL_BUDGET_FOR_GIT = 500;
    private final BudgetedContextSelector contextSelector;
    private final TokenEstimator tokenEstimator;
    private final SkillContextSelector skillContextSelector;
    ContextBudgetAllocator(BudgetedContextSelector selector, TokenEstimator estimator) {
        contextSelector = selector; tokenEstimator = estimator;
        skillContextSelector = new SkillContextSelector(estimator);
    }
    Selections select(ContextRequest request, NativeSources sources, List<ContextFragment> mergedTaskFragments) {
        var instructionFragments = sources.instructionFragments();
        var skillActivation = sources.skillActivation();
        var gitContext = sources.gitContext();
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
        return new Selections(instructionBudget, instructionSelection, skillBudget, skillSelection,
                gitBudget, gitSelection, combined);
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

        int available = 0;
        for (ContextFragment fragment : fragments) {
            available = (int) Math.min(Integer.MAX_VALUE, (long) available
                    + tokenEstimator.estimate(com.nexus.security.SensitiveContentRedactor.redact(fragment.content())));
        }
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
            availableTokens = (int) Math.min(Integer.MAX_VALUE, (long) availableTokens + selection.availableEstimatedTokens());
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

}
