package com.nexus.context.source.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Métadonnées de découverte d'un Agent Skill.
 *
 * <p>Le corps complet de SKILL.md n'est volontairement pas conservé ici afin
 * de respecter la divulgation progressive. Il ne sera chargé qu'après
 * sélection.</p>
 */
public record SkillDescriptor(
        String id,
        String provider,
        String name,
        String description,
        Path skillRoot,
        Path definitionPath,
        String license,
        String compatibility,
        Map<String, String> metadata,
        List<String> allowedTools,
        List<SkillResourceDescriptor> resources,
        int priority,
        List<String> reasons) {

    public SkillDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(skillRoot, "skillRoot");
        Objects.requireNonNull(definitionPath, "definitionPath");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(reasons, "reasons");
        metadata = Map.copyOf(metadata);
        allowedTools = List.copyOf(allowedTools);
        resources = List.copyOf(resources);
        reasons = List.copyOf(reasons);
        if (priority < 0 || priority > 100) {
            throw new IllegalArgumentException("priority must be between 0 and 100");
        }
    }
}
