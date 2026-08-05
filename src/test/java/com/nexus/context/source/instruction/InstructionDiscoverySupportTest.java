package com.nexus.context.source.instruction;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionDiscoverySupportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void respectsIgnoreRulesThatApplyToTheRootOfASubScan() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.createDirectories(projectRoot.resolve(".github/instructions"));
        Files.writeString(projectRoot.resolve(".gitignore"), ".github/instructions/\n");
        Files.writeString(
                projectRoot.resolve(".github/instructions/java.instructions.md"),
                "---\napplyTo: '**/*.java'\n---\nsecret instruction\n");

        assertTrue(InstructionDiscoverySupport.findFilesBelow(
                project(projectRoot),
                Path.of(".github/instructions"),
                ".instructions.md").isEmpty());
    }

    @Test
    void loadsParentIgnoreScopesBeforeEnteringASubScan() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("nested-project"));
        Files.createDirectories(projectRoot.resolve(".github/instructions"));
        Files.writeString(projectRoot.resolve(".github/.nexusignore"), "instructions/\n");
        Files.writeString(
                projectRoot.resolve(".github/instructions/java.instructions.md"),
                "---\napplyTo: '**/*.java'\n---\nsecret instruction\n");

        assertTrue(InstructionDiscoverySupport.findFilesBelow(
                project(projectRoot),
                Path.of(".github/instructions"),
                ".instructions.md").isEmpty());
    }

    private static ProjectDescriptor project(Path root) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "instructions",
                root,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }
}
