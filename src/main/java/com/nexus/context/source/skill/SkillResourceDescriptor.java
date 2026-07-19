package com.nexus.context.source.skill;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Métadonnée légère sur une ressource associée à un Agent Skill.
 * Le contenu n'est pas chargé pendant la découverte.
 */
public record SkillResourceDescriptor(
        Path path,
        SkillResourceType type,
        long sizeBytes) {

    public SkillResourceDescriptor {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }
}
