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
    void budgetsTheExposedContentWhenRedactionShrinksOrGrowsIt() {
        for (String raw : List.of("password=12345678", "password=" + "s".repeat(200))) {
            String safe = com.nexus.security.SensitiveContentRedactor.redact(raw);
            SkillDescriptor descriptor = new SkillDescriptor("test", "test", "test", "test",
                    Path.of("skills/test"), Path.of("skills/test/SKILL.md"), null, null,
                    Map.of(), List.of(), List.of(), 80, List.of());
            ActivatedSkill skill = new ActivatedSkill(descriptor, 0.8, raw, List.of());
            // Un token par caractère rend la frontière exacte observable même pour +1 caractère.
            SkillContextSelector selector = new SkillContextSelector(CharSequence::length);
            ContextSelectionResult exact = selector.select(List.of(skill), safe.length(), true);
            assertEquals(safe, exact.items().getFirst().content());
            assertEquals(safe.length(), exact.availableEstimatedTokens());
            assertEquals(safe.length(), exact.selectedEstimatedTokens());
            assertEquals(safe.length(), exact.items().getFirst().estimatedTokens());
            assertTrue(selector.select(List.of(skill), safe.length() - 1, true).items().isEmpty());
            var bundle = new com.nexus.context.ContextBundle(exact.items(), safe.length(),
                    exact.selectedEstimatedTokens(), exact.excluded(), Map.of());
            assertTrue(bundle.estimatedTokens() <= bundle.tokenBudget());
            assertEquals(bundle.items().getFirst().content().length(), bundle.estimatedTokens());
        }
    }

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
