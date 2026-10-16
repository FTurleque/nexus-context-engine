package com.nexus.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    ProjectDescriptor save(ProjectDescriptor project);

    Optional<ProjectDescriptor> findById(UUID projectId);

    /** Renvoie les projets connus, sans doublons et dans l'ordre demandé. */
    default List<ProjectDescriptor> findByIds(List<UUID> projectIds) {
        return projectIds.stream().distinct().map(this::findById)
                .flatMap(Optional::stream).toList();
    }

    Optional<ProjectDescriptor> findByRootPath(Path rootPath);

    List<ProjectDescriptor> findAll();
}
