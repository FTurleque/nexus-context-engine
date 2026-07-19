package com.nexus.context.source.skill;

import java.util.List;

/**
 * Résultat brut d'un provider de skills.
 */
public record SkillProviderResult(
        List<SkillDescriptor> skills,
        List<String> diagnostics) {

    public SkillProviderResult {
        skills = List.copyOf(skills);
        diagnostics = List.copyOf(diagnostics);
    }
}
