package com.nexus.context.source.skill;

import com.nexus.context.ContextSelectionResult;
import com.nexus.token.HeuristicTokenEstimator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillContextSelectorTest {

    @Test
    void excludesOversizedSkillInsteadOfTruncatingIt() {
        SkillDescriptor descriptor = new SkillDescriptor(
                "test:large-skill",
                "test",
                "large-skill",
                "A deliberately large skill used to validate strict skill budgeting.",
                Path.of(".agents/skills/large-skill"),
                Path.of(".agents/skills/large-skill/SKILL.md"),
                null,
                null,
                Map.of(),
                List.of(),
                List.of(),
                80,
                List.of());
        ActivatedSkill activated = new ActivatedSkill(
                descriptor,
                0.8d,
                "x".repeat(2_000),
                List.of("selected"));

        ContextSelectionResult result = new SkillContextSelector(new HeuristicTokenEstimator())
                .select(List.of(activated), 20, true);

        assertTrue(result.items().isEmpty());
        assertEquals(0, result.selectedEstimatedTokens());
        assertEquals(0, result.truncatedItems());
        assertFalse(result.excluded().isEmpty());
        assertTrue(result.excluded().getFirst().contains("ne sont pas tronqués"));
    }
}
