package com.nexus.context.source.skill;

import com.nexus.project.IndexStatus;
import com.nexus.project.ProjectDescriptor;
import com.nexus.project.ProjectSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSkillsRegistryProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversRegistryMetadataBeforeLoadingTheSelectedSkillBody() throws Exception {
        write(".nexus/registry/skills/shared/development/java-code-review/SKILL.md", """
                ---
                name: java-code-review
                displayName: Java Code Review
                description: Review Java code for defects, risks and maintainability issues.
                version: 1.0.0
                status: stable
                category: development
                compatibility:
                  - generic
                  - github-copilot
                  - claude-code
                license: MIT
                ---
                REGISTRY_SKILL_BODY_LOADED_ONLY_AFTER_SELECTION
                """);

        ProjectDescriptor project = project();
        SkillProviderResult providerResult = new LocalAgentSkillsProvider().discover(
                new SkillSourceQuery(project, true));

        SkillDescriptor registrySkill = providerResult.skills().stream()
                .filter(skill -> skill.provider().equals("ai-skills-registry"))
                .findFirst()
                .orElseThrow();

        assertEquals("java-code-review", registrySkill.name());
        assertEquals(60, registrySkill.priority());
        assertTrue(registrySkill.definitionPath().toString().replace('\\', '/')
                .startsWith(".nexus/registry/skills/"));
        assertFalse(registrySkill.toString().contains("REGISTRY_SKILL_BODY_LOADED_ONLY_AFTER_SELECTION"));

        SkillActivationResult activation = new SkillLoader().load(
                project,
                List.of(new SkillMatch(registrySkill, 0.9d, List.of("test selection"))));

        assertEquals(1, activation.skills().size());
        assertTrue(activation.skills().getFirst().content()
                .contains("REGISTRY_SKILL_BODY_LOADED_ONLY_AFTER_SELECTION"));
    }

    @Test
    void keepsProjectLocalSkillWhenRegistryContainsTheSameName() throws Exception {
        write(".agents/skills/java-code-review/SKILL.md", """
                ---
                name: java-code-review
                description: Project-specific Java review rules.
                ---
                LOCAL_BODY
                """);
        write(".nexus/registry/skills/shared/development/java-code-review/SKILL.md", """
                ---
                name: java-code-review
                description: Shared registry Java review rules.
                ---
                REGISTRY_BODY
                """);

        SkillDiscoveryResult result = new SkillDiscoveryService().discover(
                List.of(new LocalAgentSkillsProvider()),
                new SkillSourceQuery(project(), true));

        assertEquals(1, result.skills().size());
        assertEquals("local-agent-skills", result.skills().getFirst().provider());
        assertEquals(80, result.skills().getFirst().priority());
        assertTrue(result.deduplicatedSkills().stream()
                .anyMatch(message -> message.contains(".nexus/registry")));
    }

    private ProjectDescriptor project() {
        return new ProjectDescriptor(
                UUID.randomUUID(),
                "registry-test",
                temporaryDirectory,
                ProjectSourceType.LOCAL,
                Set.of(),
                Set.of(),
                null,
                IndexStatus.NOT_INDEXED);
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
