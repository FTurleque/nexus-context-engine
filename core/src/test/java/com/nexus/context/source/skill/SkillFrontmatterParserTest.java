package com.nexus.context.source.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFrontmatterParserTest {

    @TempDir
    Path temporaryDirectory;

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void rejectsAliasedCollectionsBeforeTheyCanBeExpanded() throws Exception {
        StringBuilder yaml = new StringBuilder("a0: &a0 [x, x]\n");
        for (int index = 1; index <= 18; index++) {
            yaml.append("a%d: &a%d [*a%d, *a%d]%n".formatted(index, index, index - 1, index - 1));
        }
        yaml.append("metadata:\n  amplified: *a18\n");
        Path skill = writeSkill(yaml.toString());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(skill));
    }

    @Test
    void rejectsNestedValuesInEveryStringFieldWithoutCallingCollectionToString() throws Exception {
        for (String field : new String[]{"license: [nested]", "compatibility: {nested: value}",
                "metadata: {key: [nested]}", "metadata: {[nested]: value}",
                "allowed-tools: [[nested]]", "allowed-tools: {nested: value}"}) {
            Path skill = writeSkill(field + "\n");
            assertThrows(IllegalArgumentException.class, () -> parser.parse(skill), field);
        }
    }

    @Test
    void boundsCumulativeDecodedScalarAliases() throws Exception {
        String yaml = "payload: &payload " + "x".repeat(20_000)
                + "\nmetadata:\n  a: *payload\n  b: *payload\n  c: *payload\n  d: *payload\n";
        Path skill = writeSkill(yaml);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> parser.parse(skill));
        assertTrue(failure.getMessage().contains("décodées trop volumineuses"), failure.getMessage());
    }

    @Test
    void acceptsStringMetadataAndFlatToolLists() throws Exception {
        Path skill = writeSkill("license: MIT\nmetadata: {version: '1.0'}\nallowed-tools: [Read, 'Bash(pdfinfo:*)']\n");
        SkillFrontmatter parsed = parser.parse(skill);
        assertEquals("1.0", parsed.metadata().get("version"));
        assertEquals(java.util.List.of("Read", "Bash(pdfinfo:*)"), parsed.allowedTools());
    }

    private Path writeSkill(String fields) throws IOException {
        Path skill = Files.createDirectories(temporaryDirectory.resolve("audit-skill")).resolve("SKILL.md");
        Files.writeString(skill, "---\nname: audit-skill\ndescription: Synthetic audit\n" + fields + "---\nBody\n");
        return skill;
    }

    @Test
    void rejectsSingleHugeFrontmatterLineAtPhysicalDiscoveryByteLimit() throws Exception {
        Path skillDirectory = Files.createDirectories(temporaryDirectory.resolve("giant"));
        Path skill = skillDirectory.resolve("SKILL.md");
        String hugeLine = "x".repeat((int) SkillFrontmatterParser.MAX_DISCOVERY_BYTES + 1_024);
        Files.writeString(skill, "---\n" + hugeLine);

        IOException failure = assertThrows(IOException.class, () -> parser.parse(skill));

        assertTrue(failure.getMessage().contains("maximum " + SkillFrontmatterParser.MAX_DISCOVERY_BYTES + " octets"),
                failure.getMessage());
    }
}
