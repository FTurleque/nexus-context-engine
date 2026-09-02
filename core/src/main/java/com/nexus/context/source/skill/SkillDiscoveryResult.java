package com.nexus.context.source.skill;

import java.util.List;

/**
 * Catalogue de skills normalisé après agrégation et déduplication.
 */
public record SkillDiscoveryResult(
        List<SkillDescriptor> skills,
        List<String> deduplicatedSkills,
        List<String> diagnostics) {

    public SkillDiscoveryResult {
        skills = List.copyOf(skills);
        deduplicatedSkills = List.copyOf(deduplicatedSkills);
        diagnostics = List.copyOf(diagnostics);
    }
}
