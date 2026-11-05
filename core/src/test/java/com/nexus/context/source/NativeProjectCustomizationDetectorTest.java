package com.nexus.context.source;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import com.nexus.testsupport.SymbolicLinks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NativeProjectCustomizationDetectorTest {

    @TempDir
    Path temporaryDirectory;

    private Path projectRoot;

    @BeforeEach
    void createProjectRoot() throws IOException {
        projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
    }

    @Test
    void rejectsAncestorSymlinkForAgentProfiles() throws Exception {
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside-agents"));
        Files.writeString(outside.resolve("escaped.md"), "outside agent profile");

        Path github = Files.createDirectories(projectRoot.resolve(".github"));
        SymbolicLinks.createOrSkip(github.resolve("agents"), outside);

        Map<String, java.util.List<String>> detected = new NativeProjectCustomizationDetector().detect(project());

        assertFalse(detected.containsKey("agentProfiles"));
    }

    @Test
    void rejectsFinalSymlinkForOperationalConfiguration() throws Exception {
        Path outside = temporaryDirectory.resolve("outside-settings.json");
        Files.writeString(outside, "{\"outside\":true}");

        Path claude = Files.createDirectories(projectRoot.resolve(".claude"));
        SymbolicLinks.createOrSkip(claude.resolve("settings.json"), outside);

        Map<String, java.util.List<String>> detected = new NativeProjectCustomizationDetector().detect(project());

        assertFalse(detected.containsKey("operationalConfigurations"));
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "native-customization-test",
                projectRoot,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }
}
