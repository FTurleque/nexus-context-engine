package io.github.fturleque.nexus.index.scan;

import io.github.fturleque.nexus.index.ScannedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        List<String> paths = new ProjectScanner().scan(temporaryDirectory).stream()
                .map(ScannedFile::relativePath)
                .toList();

        assertEquals(List.of(
                "module/src/main/java/demo/Module.java",
                "src/main/java/demo/App.java"), paths);
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
