package io.github.fturleque.nexus.project;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ProjectDescriptor(
        UUID id,
        String name,
        Path rootPath,
        ProjectSourceType sourceType,
        Set<String> languages,
        Set<String> technologies,
        Instant lastIndexedAt,
        IndexStatus indexStatus) {

    public ProjectDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rootPath, "rootPath");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(languages, "languages");
        Objects.requireNonNull(technologies, "technologies");
        Objects.requireNonNull(indexStatus, "indexStatus");
        languages = Set.copyOf(languages);
        technologies = Set.copyOf(technologies);
    }
}
