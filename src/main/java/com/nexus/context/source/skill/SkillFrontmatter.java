package com.nexus.context.source.skill;

import java.util.List;
import java.util.Map;

/**
 * Métadonnées normalisées extraites du frontmatter YAML d'un SKILL.md.
 */
record SkillFrontmatter(
        String name,
        String description,
        String license,
        String compatibility,
        Map<String, String> metadata,
        List<String> allowedTools) {

    SkillFrontmatter {
        metadata = Map.copyOf(metadata);
        allowedTools = List.copyOf(allowedTools);
    }
}
