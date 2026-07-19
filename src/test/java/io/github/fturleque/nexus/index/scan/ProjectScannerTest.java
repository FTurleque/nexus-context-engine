package io.github.fturleque.nexus.index.scan;

import io.github.fturleque.nexus.index.FileCategory;
import io.github.fturleque.nexus.index.ScannedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectScannerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void respectsGitignoreNexusignoreAndBuiltInExclusions() throws Exception {
        write("src/main/java/demo/App.java", "package demo; class App {}\n");
        write("target/generated/Generated.java", "class Generated {}\n");
        write("ignored/Ignored.java", "class Ignored {}\n");
        write("module/private/Private.java", "class Private {}\n");
        write("module/src/main/java/demo/Module.java", "package demo; class Module {}\n");
        write(".gitignore", "ignored/\n");
        write("module/.nexusignore", "private/\n");

        List<String> paths = scanPaths();

        assertEquals(List.of(
                "module/src/main/java/demo/Module.java",
                "src/main/java/demo/App.java"), paths);
    }

    @Test
    void respectsGitignoreNegationRules() throws Exception {
        write("Drop.java", "class Drop {}\n");
        write("Keep.java", "class Keep {}\n");
        write(".gitignore", "*.java\n!Keep.java\n");

        assertEquals(List.of("Keep.java"), scanPaths());
    }

    @Test
    void classifiesDocumentationInstructionsAgentProfilesAndSkills() throws Exception {
        write("README.md", "documentation");
        write("AGENTS.md", "instructions");
        write(".github/instructions/java.instructions.md", "---\napplyTo: \"**/*.java\"\n---\nrule");
        write(".github/agents/reviewer.agent.md", "agent");
        write(".github/skills/testing/SKILL.md", "skill");

        Map<String, FileCategory> categories = new ProjectScanner().scan(temporaryDirectory).stream()
                .collect(Collectors.toMap(ScannedFile::relativePath, ScannedFile::category));

        assertEquals(FileCategory.DOCUMENTATION, categories.get("README.md"));
        assertEquals(FileCategory.INSTRUCTION, categories.get("AGENTS.md"));
        assertEquals(FileCategory.INSTRUCTION, categories.get(".github/instructions/java.instructions.md"));
        assertEquals(FileCategory.AGENT_PROFILE, categories.get(".github/agents/reviewer.agent.md"));
        assertEquals(FileCategory.SKILL, categories.get(".github/skills/testing/SKILL.md"));
    }

    private List<String> scanPaths() throws Exception {
        return new ProjectScanner().scan(temporaryDirectory).stream()
                .map(ScannedFile::relativePath)
                .toList();
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
