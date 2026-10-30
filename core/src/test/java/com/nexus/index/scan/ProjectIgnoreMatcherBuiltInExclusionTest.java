package com.nexus.index.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIgnoreMatcherBuiltInExclusionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void excludesClaudeWorktreesAtAnyDepthIgnoringCase() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project")).toRealPath();
        ProjectIgnoreMatcher matcher = new ProjectIgnoreMatcher(root);

        assertTrue(matcher.isIgnored(root.resolve(".claude/worktrees"), true));
        assertTrue(matcher.isIgnored(root.resolve(".claude/worktrees/demo/AGENTS.md"), false));
        assertTrue(matcher.isIgnored(root.resolve("module/.claude/worktrees/demo/src/App.java"), false));
        assertTrue(matcher.isIgnored(root.resolve(".Claude/WorkTrees/demo/README.md"), false));
    }

    @Test
    void keepsNeighbouringPathsThatDoNotFormTheSequence() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("project")).toRealPath();
        ProjectIgnoreMatcher matcher = new ProjectIgnoreMatcher(root);

        assertFalse(matcher.isIgnored(root.resolve(".claude"), true));
        assertFalse(matcher.isIgnored(root.resolve(".claude/skills/exemple/SKILL.md"), false));
        assertFalse(matcher.isIgnored(root.resolve(".claude/worktrees-notes.md"), false));
        assertFalse(matcher.isIgnored(root.resolve("docs/worktrees/guide.md"), false));
        assertFalse(matcher.isIgnored(root.resolve("worktrees/.claude/notes.md"), false));
        assertFalse(matcher.isIgnored(root.resolve(".claude/other/worktrees/notes.md"), false));
    }
}
