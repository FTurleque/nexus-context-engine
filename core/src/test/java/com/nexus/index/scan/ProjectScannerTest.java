package com.nexus.index.scan;

import com.nexus.index.FileCategory;
import com.nexus.index.ScannedFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        write(".github/skills/testing/references/testing-guide.md", "skill reference");

        Map<String, FileCategory> categories = new ProjectScanner().scan(temporaryDirectory).stream()
                .collect(Collectors.toMap(ScannedFile::relativePath, ScannedFile::category));

        assertEquals(FileCategory.DOCUMENTATION, categories.get("README.md"));
        assertEquals(FileCategory.INSTRUCTION, categories.get("AGENTS.md"));
        assertEquals(FileCategory.INSTRUCTION, categories.get(".github/instructions/java.instructions.md"));
        assertEquals(FileCategory.AGENT_PROFILE, categories.get(".github/agents/reviewer.agent.md"));
        assertEquals(FileCategory.SKILL, categories.get(".github/skills/testing/SKILL.md"));
        assertEquals(FileCategory.SKILL, categories.get(".github/skills/testing/references/testing-guide.md"));
    }

    @Test
    void detectsAdditionalLanguagesAndTheirCommonTestConventions() throws Exception {
        write("src/main/kotlin/demo/App.kt", "class App");
        write("src/test/kotlin/demo/AppTest.kt", "class AppTest");
        write("src/frontend/app.tsx", "export const App = () => null;");
        write("src/frontend/app.test.ts", "test('app', () => {});");
        write("python/service.py", "def run(): pass");
        write("tests/test_service.py", "def test_run(): pass");
        write("db/schema.sql", "create table demo(id integer);");
        write("scripts/tool.js", "export function tool() {}");
        write("legacy.rb", "puts 'ignored'");

        Map<String, ScannedFile> files = new ProjectScanner().scan(temporaryDirectory).stream()
                .collect(Collectors.toMap(ScannedFile::relativePath, file -> file));

        assertEquals("kotlin", files.get("src/main/kotlin/demo/App.kt").language());
        assertEquals(FileCategory.SOURCE, files.get("src/main/kotlin/demo/App.kt").category());
        assertEquals(FileCategory.TEST, files.get("src/test/kotlin/demo/AppTest.kt").category());
        assertEquals("typescript", files.get("src/frontend/app.tsx").language());
        assertEquals(FileCategory.TEST, files.get("src/frontend/app.test.ts").category());
        assertEquals("python", files.get("python/service.py").language());
        assertEquals(FileCategory.TEST, files.get("tests/test_service.py").category());
        assertEquals("sql", files.get("db/schema.sql").language());
        assertEquals("javascript", files.get("scripts/tool.js").language());
        assertEquals(8, files.size());
    }

    @Test
    void rejectsSymbolicLinksInsteadOfReadingTargetsOutsideTheRepository() throws Exception {
        Path projectRoot = Files.createDirectories(temporaryDirectory.resolve("symlink-project"));
        Path outside = temporaryDirectory.resolve("outside-secret.java");
        Files.writeString(outside, "class OutsideSecret {}\n");
        Path link = projectRoot.resolve("LinkedSecret.java");

        Assumptions.assumeTrue(createSymbolicLink(link, outside),
                "Les liens symboliques ne sont pas disponibles dans cet environnement");

        ProjectScanResult result = new ProjectScanner().scanWithDiagnostics(projectRoot);

        assertTrue(result.files().isEmpty());
        assertEquals(1, result.skippedFiles());
        assertTrue(result.diagnostics().stream().anyMatch(message ->
                message.contains("LinkedSecret.java") && message.contains("lien symbolique")));
    }

    @Test
    void boundsDirectoryOnlyTraversalWithTheGlobalEntryBudget() throws Exception {
        Path projectRoot = Files.createDirectory(temporaryDirectory.resolve("directory-explosion"));
        Files.createDirectory(projectRoot.resolve("dir-1"));
        Files.createDirectory(projectRoot.resolve("dir-2"));
        Files.createDirectory(projectRoot.resolve("dir-3"));
        Files.createDirectory(projectRoot.resolve("dir-4"));

        IOException failure = assertThrows(
                IOException.class,
                () -> new ProjectScanner(1024L, 3, 1024L).scan(projectRoot));

        assertTrue(failure.getMessage().contains("4 entrées visitées > limite 3"));
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

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return false;
        }
    }
}
