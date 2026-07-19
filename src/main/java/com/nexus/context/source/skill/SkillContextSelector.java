package com.nexus.context.source.skill;

import com.nexus.context.ContextItem;
import com.nexus.context.ContextSelectionResult;
import com.nexus.search.CandidateType;
import com.nexus.token.TokenEstimator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applique le budget dédié aux skills sans jamais tronquer leurs instructions.
 */
public final class SkillContextSelector {

    private final TokenEstimator tokenEstimator;

    public SkillContextSelector(TokenEstimator tokenEstimator) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
    }

    public ContextSelectionResult select(
            List<ActivatedSkill> activatedSkills,
            int budget,
            boolean explain) {
        int availableTokens = activatedSkills.stream()
                .mapToInt(skill -> tokenEstimator.estimate(skill.content()))
                .sum();
        if (activatedSkills.isEmpty()) {
            return new ContextSelectionResult(List.of(), List.of(), 0, 0, 0);
        }

        List<ContextItem> items = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        int selectedTokens = 0;

        for (ActivatedSkill activated : activatedSkills) {
            int estimatedTokens = tokenEstimator.estimate(activated.content());
            int remaining = Math.max(0, budget - selectedTokens);
            if (estimatedTokens > remaining) {
                if (explain) {
                    excluded.add(repositoryPath(activated.descriptor().definitionPath())
                            + " exclu : skill complet de " + estimatedTokens
                            + " tokens estimés, " + remaining
                            + " disponibles ; les skills ne sont pas tronqués");
                }
                continue;
            }

            List<String> reasons = new ArrayList<>(activated.reasons());
            reasons.add("skill activé intégralement sous le budget dédié");
            Map<String, Double> components = new LinkedHashMap<>();
            components.put("skillMetadataScore", activated.score());

            items.add(new ContextItem(
                    CandidateType.SKILL,
                    activated.descriptor().definitionPath(),
                    null,
                    1,
                    Math.max(1, activated.content().split("\\R", -1).length),
                    activated.content(),
                    activated.score(),
                    components,
                    reasons,
                    estimatedTokens,
                    false));
            selectedTokens += estimatedTokens;
        }

        return new ContextSelectionResult(
                items,
                excluded,
                availableTokens,
                selectedTokens,
                0);
    }

    private static String repositoryPath(java.nio.file.Path path) {
        return path.toString().replace('\\', '/');
    }
}
