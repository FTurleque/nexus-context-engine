package com.nexus.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ProjectRegistry {

    private final ProjectRepository repository;

    public ProjectRegistry(ProjectRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ProjectDescriptor register(Path rootPath, String requestedName) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        Path normalizedRoot = rootPath.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("Le chemin du projet n'est pas un répertoire : " + normalizedRoot);
        }
        Path canonicalRoot = normalizedRoot.toRealPath();

        ProjectDescriptor existing = repository.findByRootPath(canonicalRoot).orElse(null);
        if (existing != null) {
            return existing;
        }

        return repository.save(new ProjectDescriptor(
                UUID.randomUUID(),
                resolveName(canonicalRoot, requestedName),
                canonicalRoot,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED));
    }

    public List<ProjectDescriptor> list() {
        return repository.findAll();
    }

    public ProjectDescriptor get(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Projet NEXUS introuvable : " + projectId));
    }

    private static String resolveName(Path rootPath, String requestedName) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        Path fileName = rootPath.getFileName();
        return fileName == null ? rootPath.toString() : fileName.toString();
    }
}
