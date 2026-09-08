package com.nexus.api;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;

class ApiMapperPrivacyTest {

    @Test
    void omitsAbsoluteProjectRootFromPublicProjectRepresentation() {
        ProjectDescriptor project = new ProjectDescriptor(
                UUID.randomUUID(),
                "private-project",
                Path.of("/home/alice/private-project"),
                ProjectSourceType.LOCAL,
                Set.of("java"),
                Set.of(),
                null,
                IndexStatus.READY);

        assertNull(ApiMapper.project(project).rootPath());
    }
}
