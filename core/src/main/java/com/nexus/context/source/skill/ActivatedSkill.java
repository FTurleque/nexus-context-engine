package com.nexus.context.source.skill;

import java.util.List;
import java.util.Objects;

/**
 * Skill dont le SKILL.md complet a été chargé après sélection.
 */
public record ActivatedSkill(
        SkillDescriptor descriptor,
        double score,
        String content,
        List<String> reasons) {

    public ActivatedSkill {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
        if (score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
    }
}
