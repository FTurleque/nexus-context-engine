package com.nexus.context.source.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFrontmatterParserTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSingleHugeFrontmatterLineAtPhysicalDiscoveryByteLimit() throws Exception {
        Path skillDirectory = Files.createDirectories(temporaryDirectory.resolve("giant"));
        Path skill = skillDirectory.resolve("SKILL.md");
        String hugeLine = "x".repeat((int) SkillFrontmatterParser.MAX_DISCOVERY_BYTES + 1_024);
        Files.writeString(skill, "---\n" + hugeLine);

        IOException failure = assertThrows(IOException.class, () -> new SkillFrontmatterParser().parse(skill));

        assertTrue(failure.getMessage().contains("maximum " + SkillFrontmatterParser.MAX_DISCOVERY_BYTES + " octets"),
                failure.getMessage());
    }
}
