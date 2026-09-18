package com.nexus.context;

import com.nexus.context.source.ContextSourceDiscoveryResult;
import com.nexus.context.source.git.GitContextResult;
import com.nexus.context.source.skill.SkillActivationResult;
import com.nexus.context.source.skill.SkillDiscoveryResult;
import com.nexus.context.source.skill.SkillMatch;

import java.util.List;

/** Étape interne et déterministe de construction du contexte. */
final class ContextPipelineState {
    record NativeSources(ContextSourceDiscoveryResult nativeDiscovery,
            List<ContextFragment> instructionFragments, SkillDiscoveryResult skillDiscovery,
            List<SkillMatch> skillMatches, SkillActivationResult skillActivation, GitContextResult gitContext) { }
    record TaskFragments(ContextFragmentFactory.MaterializationResult materialization,
            List<ContextFragment> deduplicated, List<ContextFragment> merged) {
        int duplicates() { return materialization.fragments().size() - deduplicated.size(); }
    }
    record Selections(int instructionBudget, ContextSelectionResult instructionSelection,
            int skillBudget, ContextSelectionResult skillSelection, int gitBudget,
            ContextSelectionResult gitSelection, ContextSelectionResult combined) { }
}
