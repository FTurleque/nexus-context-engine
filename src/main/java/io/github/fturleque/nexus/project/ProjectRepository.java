package io.github.fturleque.nexus.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    ProjectDescriptor save(ProjectDescriptor project);

    Optional<ProjectDescriptor> findById(UUID projectId);

    Optional<ProjectDescriptor> findByRootPath(Path rootPath);

    List<ProjectDescriptor> findAll();
}
