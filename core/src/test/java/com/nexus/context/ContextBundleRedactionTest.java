package com.nexus.context;

import com.nexus.search.CandidateType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBundleRedactionTest {

    @Test
    void redactsEveryItemAtTheBundleBoundary() {
        ContextItem instruction = new ContextItem(
                CandidateType.INSTRUCTION,
                Path.of("AGENTS.md"),
                null,
                1,
                1,
                "password=\"P@ssw0rd!2026#prod\"",
                1.0d,
                Map.of(),
                List.of(),
                12,
                false);
        ContextItem skill = new ContextItem(
                CandidateType.SKILL,
                Path.of(".agents", "skills", "deploy", "SKILL.md"),
                null,
                1,
                1,
                "token=ghp_abcdefghijklmnopqrstuvwxyz123456",
                1.0d,
                Map.of(),
                List.of(),
                12,
                false);
        ContextItem git = new ContextItem(
                CandidateType.GIT,
                Path.of(".nexus", "git", "working-tree-diff.md"),
                null,
                1,
                1,
                "+ client_secret='0123456789!@#$%^&*'",
                1.0d,
                Map.of(),
                List.of(),
                12,
                false);

        ContextBundle bundle = new ContextBundle(
                List.of(instruction, skill, git),
                100,
                36,
                List.of(),
                Map.of());

        String content = bundle.items().stream()
                .map(ContextItem::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(content.contains("P@ssw0rd!2026#prod"));
        assertFalse(content.contains("ghp_abcdefghijklmnopqrstuvwxyz123456"));
        assertFalse(content.contains("0123456789!@#$%^&*"));
        assertTrue(content.contains("[REDACTED]"));
    }
}
