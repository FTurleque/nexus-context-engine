package com.nexus.context.source.skill;

import java.util.List;

/**
 * Résultat du chargement des SKILL.md sélectionnés.
 */
public record SkillActivationResult(
        List<ActivatedSkill> skills,
        List<String> diagnostics) {

    public SkillActivationResult {
        skills = List.copyOf(skills);
        diagnostics = List.copyOf(diagnostics);
    }
}
