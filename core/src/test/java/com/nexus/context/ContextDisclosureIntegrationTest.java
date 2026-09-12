package com.nexus.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.application.NexusApplication;
import com.nexus.config.NexusPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ContextDisclosureIntegrationTest {
    @TempDir Path temporary;

    @Test
    void deletedIndexedFilesNeverDiscloseEitherProjectRootInCompleteBundles() throws Exception {
        try (var app = NexusApplication.create(new NexusPaths(temporary.resolve("home")))) {
            var first = app.registerProject(Files.createDirectory(temporary.resolve("first")), "first");
            var second = app.registerProject(Files.createDirectory(temporary.resolve("second")), "second");
            for (var project : List.of(first, second)) {
                Path source = Files.writeString(project.rootPath().resolve("Needle.java"),
                        "class Needle { void needle() {} }");
                app.index(project.id(), true, false);
                Files.delete(source);
            }
            ObjectMapper mapper = new ObjectMapper();
            var bundle = app.context(first.id(), "needle", 500, Set.of(), Map.of(), true).bundle();
            assertFalse(((List<?>) bundle.metadata().get("taskMaterializationDiagnostics")).isEmpty());
            String local = mapper.writeValueAsString(bundle);
            var federated = app.contextAcrossProjects(List.of(first.id(), second.id()),
                    "needle", 1000, Set.of(), Map.of(), true).bundle();
            String multi = mapper.writeValueAsString(federated);
            for (Path root : List.of(first.rootPath(), second.rootPath(), temporary.resolve("home"))) {
                for (String payload : List.of(local, multi)) {
                    assertFalse(payload.contains(root.toString().replace("\\", "\\\\")), payload);
                    assertFalse(payload.contains(root.toString().replace('\\', '/')), payload);
                }
            }
        }
    }
}
