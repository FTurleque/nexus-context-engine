package com.nexus.context.source.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSelectorTest {

    @Test
    void selectsRelevantSkillFromNameAndDescriptionAndRejectsUnrelatedSkill() {
        SkillDescriptor pdf = skill(
                "pdf-processing",
                "Extract PDF text, fill forms and process PDF documents. Use for PDF extraction tasks.");
        SkillDescriptor database = skill(
                "database-migration",
                "Plan relational database schema migrations and SQL rollouts.");

        List<SkillMatch> matches = new SkillSelector().select(
                "extract data from a PDF form",
                List.of(database, pdf));

        assertEquals(1, matches.size());
        assertEquals("pdf-processing", matches.getFirst().skill().name());
        assertTrue(matches.getFirst().score() >= 0.22d);
        assertTrue(matches.getFirst().reasons().stream().anyMatch(reason -> reason.contains("description")));
    }

    @Test
    void explicitSkillNameWinsDeterministically() {
        SkillDescriptor skill = skill(
                "code-review",
                "Review source code for correctness and maintainability.");

        List<SkillMatch> matches = new SkillSelector().select(
                "use code-review on this change",
                List.of(skill));

        assertEquals(1, matches.size());
        assertTrue(matches.getFirst().reasons().stream()
                .anyMatch(reason -> reason.contains("mentionné explicitement")));
    }

    private static SkillDescriptor skill(String name, String description) {
        Path root = Path.of(".agents/skills").resolve(name);
        return new SkillDescriptor(
                "test:" + name,
                "test",
                name,
                description,
                root,
                root.resolve("SKILL.md"),
                null,
                null,
                Map.of(),
                List.of(),
                List.of(),
                80,
                List.of());
    }
}
