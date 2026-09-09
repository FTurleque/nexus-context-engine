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

    @org.junit.jupiter.api.io.TempDir Path temporary;

    @Test
    void completeRestPayloadsHideRootsAfterIndexedFilesDisappear() throws Exception {
        try (var app = com.nexus.application.NexusApplication.create(
                new com.nexus.config.NexusPaths(temporary.resolve("home")))) {
            var first = app.registerProject(java.nio.file.Files.createDirectory(temporary.resolve("first")), "first");
            var second = app.registerProject(java.nio.file.Files.createDirectory(temporary.resolve("second")), "second");
            for (var project : java.util.List.of(first, second)) {
                Path file = java.nio.file.Files.writeString(project.rootPath().resolve("Needle.java"), "class Needle {}");
                app.index(project.id(), true, false);
                java.nio.file.Files.delete(file);
            }
            var local = app.context(first.id(), "Needle", 500, Set.of(), java.util.Map.of(), true);
            var multi = app.contextAcrossProjects(java.util.List.of(first.id(), second.id()),
                    "Needle", 1000, Set.of(), java.util.Map.of(), true);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            for (Object response : java.util.List.of(
                    ApiMapper.context(new NexusApiApplicationService.ContextOperation(local.project(), local.query(),
                            true, 0, local.bundle())),
                    ApiMapper.federatedContext(new NexusApiApplicationService.FederatedContextOperation(
                            multi.projects(), multi.query(), true, 0, multi.bundle())))) {
                String payload = mapper.writeValueAsString(response);
                org.junit.jupiter.api.Assertions.assertFalse(payload.contains(temporary.toString().replace("\\", "\\\\")), payload);
                org.junit.jupiter.api.Assertions.assertFalse(payload.contains(temporary.toString().replace('\\', '/')), payload);
            }
        }
    }

    @Test
    void boundaryProtectsFutureNestedMetadataProducersAndPreservesClientQuery() throws Exception {
        var project = new ProjectDescriptor(UUID.randomUUID(), "test", temporary,
                ProjectSourceType.LOCAL, Set.of(), Set.of(), null, IndexStatus.READY);
        String clientQuery = "client /home/client/request";
        var bundle = new com.nexus.context.ContextBundle(java.util.List.of(), 50, 0, java.util.List.of(),
                java.util.Map.of("nested", java.util.List.of(java.util.Map.of("reason",
                        temporary.resolve("missing").toString() + " password=12345678")), "query", clientQuery));
        var response = ApiMapper.context(new NexusApiApplicationService.ContextOperation(project, clientQuery, true, 0, bundle));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("12345678"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains(temporary.toString().replace("\\", "\\\\")));
        org.junit.jupiter.api.Assertions.assertEquals(clientQuery, response.query());
        org.junit.jupiter.api.Assertions.assertEquals(clientQuery, response.metadata().get("query"));
    }

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
