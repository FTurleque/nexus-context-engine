package com.nexus.context.source.skill;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentSkillsProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversOnlyValidSkillsAndInventoriesResourcesWithoutLoadingTheirContent() throws Exception {
        write(temporaryDirectory, ".agents/skills/pdf-processing/SKILL.md", """
                ---
                name: pdf-processing
                description: >
                  Extract PDF text and tables. Use when handling PDF documents or forms.
                license: Apache-2.0
                compatibility: Requires a PDF-capable agent.
                metadata:
                  author: nexus-test
                  version: "1.0"
                allowed-tools: Read Bash(pdfinfo:*)
                ---
                SECRET_BODY_MARKER_SHOULD_NOT_BE_IN_DESCRIPTOR
                """);
        write(temporaryDirectory, ".agents/skills/pdf-processing/references/REFERENCE.md", "REFERENCE_BODY");
        write(temporaryDirectory, ".agents/skills/pdf-processing/scripts/extract.py", "print('SCRIPT_BODY')");
        write(temporaryDirectory, ".agents/skills/pdf-processing/assets/template.txt", "ASSET_BODY");

        write(temporaryDirectory, ".github/skills/wrong-folder/SKILL.md", """
                ---
                name: different-name
                description: Invalid because the name does not match the directory.
                ---
                body
                """);

        write(temporaryDirectory, ".claude/skills/.nexusignore", "private/\n");
        write(temporaryDirectory, ".claude/skills/private/SKILL.md", """
                ---
                name: private
                description: This skill must be ignored by nested nexusignore rules.
                ---
                body
                """);

        ProjectDescriptor project = project(temporaryDirectory, "skills-test");

        SkillProviderResult result = new LocalAgentSkillsProvider().discover(
                new SkillSourceQuery(project, true));

        assertEquals(1, result.skills().size());
        SkillDescriptor skill = result.skills().getFirst();
        assertEquals("pdf-processing", skill.name());
        assertTrue(skill.description().contains("PDF documents"));
        assertEquals("Apache-2.0", skill.license());
        assertEquals("nexus-test", skill.metadata().get("author"));
        assertTrue(skill.allowedTools().contains("Read"));
        assertEquals(3, skill.resources().size());
        assertTrue(skill.resources().stream().anyMatch(resource -> resource.type() == SkillResourceType.REFERENCE));
        assertTrue(skill.resources().stream().anyMatch(resource -> resource.type() == SkillResourceType.SCRIPT));
        assertTrue(skill.resources().stream().anyMatch(resource -> resource.type() == SkillResourceType.ASSET));
        assertFalse(skill.toString().contains("SECRET_BODY_MARKER_SHOULD_NOT_BE_IN_DESCRIPTOR"));
        assertTrue(result.diagnostics().stream().anyMatch(message -> message.contains("different-name")));
        assertFalse(result.diagnostics().stream().anyMatch(message -> message.contains("private")));
    }

    @Test
    void refusesASkillRootThatIsASymbolicLinkOutsideTheRepository() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("symlink-project"));
        Path outsideSkills = Files.createDirectories(temporaryDirectory.resolve("outside-skills"));
        write(outsideSkills, "secret/SKILL.md", """
                ---
                name: secret
                description: Must never be discovered through an external symlink.
                ---
                SECRET_SKILL_BODY
                """);
        Path agents = Files.createDirectories(projectRoot.resolve(".agents"));
        Path skillLink = agents.resolve("skills");

        Assumptions.assumeTrue(createSymbolicLink(skillLink, outsideSkills),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        SkillProviderResult result = new LocalAgentSkillsProvider().discover(
                new SkillSourceQuery(project(projectRoot, "symlink-skills"), true));

        assertTrue(result.skills().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(message ->
                message.contains(".agents/skills") && message.contains("Lien symbolique")));
    }

    private static ProjectDescriptor project(Path root, String name) {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                name,
                root,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }

    private static void write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return false;
        }
    }
}
