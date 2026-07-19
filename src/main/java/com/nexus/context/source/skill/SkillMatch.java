package com.nexus.context.source.skill;

import java.util.List;
import java.util.Objects;

/**
 * Skill dont les métadonnées correspondent à la tâche courante.
 */
public record SkillMatch(
        SkillDescriptor skill,
        double score,
        List<String> reasons) {

    public SkillMatch {
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(reasons, "reasons");
        reasons = List.copyOf(reasons);
        if (score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
    }
}
