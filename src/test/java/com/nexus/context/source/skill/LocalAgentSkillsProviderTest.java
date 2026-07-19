package com.nexus.context.source.skill;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        write(".agents/skills/pdf-processing/SKILL.md", """
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
        write(".agents/skills/pdf-processing/references/REFERENCE.md", "REFERENCE_BODY");
        write(".agents/skills/pdf-processing/scripts/extract.py", "print('SCRIPT_BODY')");
        write(".agents/skills/pdf-processing/assets/template.txt", "ASSET_BODY");

        write(".github/skills/wrong-folder/SKILL.md", """
                ---
                name: different-name
                description: Invalid because the name does not match the directory.
                ---
                body
                """);

        write(".claude/skills/.nexusignore", "private/\n");
        write(".claude/skills/private/SKILL.md", """
                ---
                name: private
                description: This skill must be ignored by nested nexusignore rules.
                ---
                body
                """);

        ProjectDescriptor project = new ProjectDescriptor(
                UUID.randomUUID(),
                "skills-test",
                temporaryDirectory,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);

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

    private void write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
